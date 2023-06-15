package cn.nannar.plugin.controller;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.nannar.plugin.bo.ConfirmBO;
import cn.nannar.plugin.bo.TaskBO;
import cn.nannar.plugin.vo.AlarmCodeVO;
import cn.nannar.plugin.vo.DeviceStatusVO;
import cn.nuoli.monitor.config.properties.GunsProperties;
import cn.nuoli.monitor.core.common.constant.HttpUrlConst;
import cn.nuoli.monitor.core.common.constant.bizenum.NormalEnum;
import cn.nuoli.monitor.core.shiro.ShiroKit;
import cn.nuoli.monitor.core.shiro.ShiroUser;
import cn.nuoli.monitor.modular.detection.dto.EnableCheckItemDTO;
import cn.nuoli.monitor.modular.detection.model.CheckItem;
import cn.nuoli.monitor.modular.detection.model.CheckSelfInspect;
import cn.nuoli.monitor.modular.detection.service.ICheckItemService;
import cn.nuoli.monitor.modular.detection.service.ICheckSelfInspectService;
import cn.nuoli.monitor.modular.robot.constant.HealthStatus;
import cn.nuoli.monitor.modular.robot.model.BotAlarmLog;
import cn.nuoli.monitor.modular.robot.service.BotAlarmLogService;
import cn.nuoli.monitor.modular.robot.service.RdpsService;
import cn.nuoli.monitor.modular.robot.service.RobotFacadeService;
import cn.stylefeng.roses.core.reqres.response.ResponseData;
import cn.stylefeng.roses.core.util.SpringContextHolder;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.mapper.EntityWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.stream.Collectors;

/**
 * @author LTJ
 * @date 2023/6/13
 */
@Slf4j
public class External928Controller {
    @Autowired
    private ICheckSelfInspectService checkSelfInspectService;
    @Autowired
    private RestTemplate restTemplate;
    @Autowired
    private ICheckItemService checkItemService;
    @Autowired
    private RobotFacadeService robotFacadeService;
    @Autowired
    private BotAlarmLogService botAlarmLogService;

    @ResponseBody
    public ResponseData listDeviceStatus(){
        /* begin ==========结构体============= */
        DeviceStatusVO deviceStatusVO = new DeviceStatusVO();
        deviceStatusVO.setStatus(NormalEnum.NORMAL.getCode());
        deviceStatusVO.setDesc("");
        /* end ============结构体============ */

        /* begin ==========获取设备列表信息============= */
        String url = new StringBuffer().append(SpringContextHolder.getBean(GunsProperties.class).getEnvironmentWebHttp()).append(HttpUrlConst.GET_REALTIME_LIST)
                .toString();
//        ShiroUser user = ShiroKit.getUser();
//        List<Integer> roleList = user.getRoleList();
        // 固定是地铁业主
        String roleIds = "2";
        // 封装参数
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        MultiValueMap<String, String> valueMap = new LinkedMultiValueMap<>(8);
        valueMap.add("roleIds", roleIds);
        HttpEntity<MultiValueMap<String, String>> requestEntity = new HttpEntity<MultiValueMap<String, String>>(valueMap, headers);

        ResponseEntity<JSONObject> response=null;
        try {
            response = restTemplate.postForEntity(url, requestEntity, JSONObject.class);
        } catch (Exception e) {
            log.error("RestEnvironmentController.realTimeList 请求环境信息错误：{}",e);
        }
        if(response==null || !response.getStatusCode().equals(HttpStatus.OK)){
            return ResponseData.success(deviceStatusVO);
        }
        JSONObject content = response.getBody();
        JSONArray data = content.getJSONArray("data");
        if(data==null){
            return ResponseData.success(deviceStatusVO);
        }
        List<CheckSelfInspect> checkSelfInspectList = data.stream().map(e -> {
            CheckSelfInspect checkSelfInspect = BeanUtil.mapToBean((Map<String,Object>) e, CheckSelfInspect.class, false, null);
            return checkSelfInspect;
        }).collect(Collectors.toList());

        // 筛选出异常的设备信息记录
        checkSelfInspectList = checkSelfInspectList.stream().filter(e -> HealthStatus.ABNORMAL.getCode().equals(e.getStatus()))
                .collect(Collectors.toList());
        /* end ============获取设备列表信息============ */
        if(CollUtil.isNotEmpty(checkSelfInspectList)){
            CheckSelfInspect checkSelfInspect = checkSelfInspectList.get(0);
            deviceStatusVO.setDesc(checkSelfInspect.getObjDesc());
        }

        return ResponseData.success(deviceStatusVO);
    }


    @ResponseBody
    public ResponseData getAlarmCode(){
        EnableCheckItemDTO enableCheckItemDTO = checkItemService.listEnableCkItemWithoutStation();
        List<CheckItem> to3CheckItemList = enableCheckItemDTO.getTo3CheckItemList();
        List<CheckItem> commonCheckItemList = enableCheckItemDTO.getCommonCheckItemList();

        List<AlarmCodeVO> alarmCodeVOList = new LinkedList<>();
        for (CheckItem checkItem : to3CheckItemList) {
            AlarmCodeVO alarmCodeVO = new AlarmCodeVO();
            alarmCodeVO.setAlarmCode(StrUtil.format("{}_{}", checkItem.getDeviceTypeId(), checkItem.getPointTypeId()));
            if(Objects.equals(checkItem.getIsTelemetry(),1)){
                alarmCodeVO.setAlarmDesc(StrUtil.format("{}超限",checkItem.getPointTypeName()));
            }else{
                alarmCodeVO.setAlarmDesc(StrUtil.format("{}异常",checkItem.getPointTypeName()));
            }
            alarmCodeVOList.add(alarmCodeVO);
        }

        for (CheckItem checkItem : commonCheckItemList) {
            AlarmCodeVO alarmCodeVO = new AlarmCodeVO();
            alarmCodeVO.setAlarmCode(StrUtil.format("{}_{}", checkItem.getDeviceTypeId(), checkItem.getPointTypeId()));
            if(Objects.equals(checkItem.getIsTelemetry(),1)){
                alarmCodeVO.setAlarmDesc(StrUtil.format("{}超限",checkItem.getPointTypeName()));
            }else{
                alarmCodeVO.setAlarmDesc(StrUtil.format("{}异常",checkItem.getPointTypeName()));
            }
            alarmCodeVOList.add(alarmCodeVO);
        }

        return ResponseData.success(alarmCodeVOList);
    }


    @ResponseBody
    public ResponseData createTask(@RequestBody TaskBO taskBO){
        JSONObject jsonObject = new JSONObject();
         List<Long> taskIdList = new LinkedList<>();
        List<TaskBO.TaskItem> taskItemList = taskBO.getTask();
        for(int i=0;i<taskItemList.size();i++){
            TaskBO.TaskItem taskItem = taskItemList.get(i);
            Long taskId = robotFacadeService.createTask(taskItem.getRobotId(), taskItem.getLaneId(), taskItem.getTrainNo(), taskItem.getDirection(), -1).orElse(null);
            if(taskId!=null){
                 robotFacadeService.resumeTask(taskId);
            }
            taskIdList.add(taskId);
        }
        jsonObject.put("taskId", taskIdList);
        return ResponseData.success(jsonObject);
    }

    @ResponseBody
    public ResponseData confirm(@RequestBody List<ConfirmBO> confirmBOS) {
        log.info("接收到：{}", confirmBOS);
        for (ConfirmBO confirmBO : confirmBOS) {
            EntityWrapper<BotAlarmLog> wrapper = new EntityWrapper<>();
            wrapper.eq("bot_inspect_id", confirmBO.getTaskId());
            wrapper.eq("pic_point_id", confirmBO.getPicPointSeq());
            wrapper.eq("arr_idx", confirmBO.getArrIdx());
            BotAlarmLog botAlarmLog = botAlarmLogService.selectOne(wrapper);
            if(botAlarmLog!=null){
                botAlarmLog.setAlarmStatus(confirmBO.getAlarmStatus());
                botAlarmLogService.updateById(botAlarmLog);
            }
        }
        return ResponseData.success();
    }
}
