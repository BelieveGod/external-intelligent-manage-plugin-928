package cn.nannar.plugin.jms;

import cn.nannar.plugin.vo.RobotAlarmVO;
import cn.nuoli.monitor.core.util.LogUtil;
import cn.nuoli.monitor.modular.robot.model.BotPicPosCfg;
import cn.nuoli.monitor.modular.robot.service.BotPicPosCfgService;
import cn.nuoli.monitor.modular.robot.vo.rdps.RdpsFrame;
import cn.nuoli.monitor.modular.robot.vo.rdps.RealTimePicPointDoneDTO;
import cn.nuoli.monitor.modular.robot.vo.rdps.ResultJsonSingleDO;
import cn.stylefeng.roses.core.util.SpringContextHolder;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.TypeReference;
import lombok.extern.slf4j.Slf4j;
import org.apache.activemq.command.ActiveMQTextMessage;
import org.apache.activemq.util.ByteSequence;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.jms.listener.SessionAwareMessageListener;
import org.springframework.validation.BindException;
import org.springframework.validation.Validator;

import javax.jms.JMSException;
import javax.jms.Session;
import java.nio.charset.StandardCharsets;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static cn.nuoli.monitor.modular.robot.constant.MsgType.NORMAL;

/**
 * @author LTJ
 * @date 2023/6/15
 */
@Slf4j
public class RobotListenner implements SessionAwareMessageListener<ActiveMQTextMessage> {

    @Override
    public void onMessage(ActiveMQTextMessage message, Session session) throws JMSException {
        final Validator validator = SpringContextHolder.getBean(Validator.class);
        final BotPicPosCfgService botPicPosCfgService = SpringContextHolder.getBean(BotPicPosCfgService.class);
        String msg=null;
        try {
            msg=message.getText();
        } catch (JMSException e) {
            log.error("onRealTimeMessage 接收文本错误",e);
            ByteSequence content = message.getContent();
            String s = new String(content.getData(), content.getOffset(), content.getLength(), StandardCharsets.UTF_8);
            log.error("s:{}", s);
        }
        if(msg!=null){
            log.info("接收：{}", msg);
        }
        // 解析成json
        RdpsFrame<RealTimePicPointDoneDTO> frame = null;
        try {
            frame= JSONObject.parseObject(msg, new TypeReference<RdpsFrame<RealTimePicPointDoneDTO>>() {
            });
        } catch (Exception e) {
            String buildLog = LogUtil.buildLog("解析json失败:{}", msg);
            log.error(buildLog,e);
            return;
        }
        // 校验
        if(!NORMAL.getMessage().equals(frame.getMsgType())){
            log.error("{} 不是普通消息", msg);
            return;
        }
        // 注意，不是完成点的时候才去校验数据
        if(!frame.getData().getFinished()){
            BindException bindException = new BindException(frame, "frame");
            validator.validate(frame, bindException);
            if (bindException.hasFieldErrors()) {
                Set<String> errorMsgs = bindException.getFieldErrors().stream().map(e->{
                    return e.getObjectName() + e.getField() + e.getDefaultMessage();
                }).collect(Collectors.toSet());
                log.error(String.join(",", errorMsgs));
                return;
            }
        }
        // 找到所属拍照点所属的任务
        RealTimePicPointDoneDTO data = frame.getData();
        Long taskId = data.getTaskId();
        String cmdSrc = data.getCmdSrc();
        if(taskId==null || cmdSrc==null){
            log.error("taskId 或 cmdSrc 为空");
            return;
        }

        BotPicPosCfg botPicPosCfg = botPicPosCfgService.selectById(data.getPicPointId());

        List<RobotAlarmVO> robotAlarmVOList = new LinkedList<>();
        List<ResultJsonSingleDO.ErrorInfo> partInfoList = data.getPartInfo();
        for(int i=0;i<partInfoList.size();i++){
            ResultJsonSingleDO.ErrorInfo errorInfo = partInfoList.get(i);
            RobotAlarmVO robotAlarmVO = new RobotAlarmVO();
            robotAlarmVO.setTaskId(data.getTaskId());
            robotAlarmVO.setCarriage(data.getCarriage());
            robotAlarmVO.setPicPointSeq(data.getPicPointId());
            robotAlarmVO.setPicPointSeqName(botPicPosCfg.getPicSeqName());
            robotAlarmVO.setArrIdx(i);
            robotAlarmVO.setDescription(errorInfo.getResultDesc());
            robotAlarmVOList.add(robotAlarmVO);
        }
        log.info("机器人告警：{}", JSONObject.toJSONString(robotAlarmVOList));
    }

}
