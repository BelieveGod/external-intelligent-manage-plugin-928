package cn.nannar.plugin.task;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.convert.Convert;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.IORuntimeException;
import cn.hutool.core.io.file.FileWriter;
import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.StrUtil;
import cn.nannar.plugin.ctx.GlobalHolder;
import cn.nannar.plugin.vo.AlarmVO;
import cn.nuoli.monitor.core.common.constant.bizenum.NormalEnum;
import cn.nuoli.monitor.core.util.CkIdentityUtil;
import cn.nuoli.monitor.core.util.NannarNameHelper;
import cn.nuoli.monitor.modular.detection.dto.EnableCheckItemDTO;
import cn.nuoli.monitor.modular.detection.model.CheckItem;
import cn.nuoli.monitor.modular.detection.service.ICheckItemService;
import cn.nuoli.monitor.modular.statistical.model.DaAlarm360Log;
import cn.nuoli.monitor.modular.statistical.model.DaAlarmLog;
import cn.nuoli.monitor.modular.statistical.service.IDaAlarm360Service;
import cn.nuoli.monitor.modular.statistical.service.IDaAlarmLogService;
import cn.nuoli.monitor.modular.train.model.TrainLog;
import cn.nuoli.monitor.modular.train.service.ITrainLogService;
import cn.stylefeng.roses.core.reqres.response.ResponseData;
import cn.stylefeng.roses.core.util.SpringContextHolder;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.baomidou.mybatisplus.mapper.EntityWrapper;
import com.baomidou.mybatisplus.plugins.Page;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.io.File;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * @author LTJ
 * @date 2023/6/14
 */
@Slf4j
public class UploadTask {
    private final List<Long> pendingTidList = new LinkedList<>();
    private final Integer COMPLETED_TRANCE_STATUS=100;

    private ITrainLogService trainLogService;
    private IDaAlarmLogService daAlarmLogService;
    private IDaAlarm360Service daAlarm360Service;
    private ICheckItemService checkItemService;
    private RestTemplate restTemplate;

    /**
     * 过车id的滑动窗口大小
     */
    private static final int TID_SLIDER_WINDOW_SIZE=10;
    private static Pattern tidListPattern = Pattern.compile("\\d*(?:,\\d+)*");

    public void upload(){
        TrainLog trainLog = readTid().orElse(null);
        if(trainLog==null){
//            log.info("暂无需要传送的过车记录");
            return;
        }
        boolean flag = handleTrainLog(trainLog);
        if(flag){
            log.info("过车：{}数据推送成功！", trainLog.getId());
        }else{
            log.info("过车：{}数据推送失败！", trainLog.getId());
        }
        saveTrainLogId(trainLog.getId());

    }

    /**
     * 使用前必须初始化一次
     */
    public void init(){
        // 创建好指定的存储文件
        File storeFile = FileUtil.file(GlobalHolder.PROPERTIES.getProperty("storeFile"));
        if(FileUtil.exist(storeFile)){
            // no-op
        }else{
            FileUtil.touch(storeFile);
            log.info("已创建tid记录文件：{}", FileUtil.getAbsolutePath(storeFile));
        }

        File pendingTidListFile = FileUtil.file(GlobalHolder.PROPERTIES.getProperty("pendingTidListFile"));
        if(FileUtil.exist(pendingTidListFile)){ // 存在的话则加载进内存
            String content = FileUtil.readUtf8String(pendingTidListFile);
            Matcher matcher = tidListPattern.matcher(content);
            if(matcher.matches()){
                if(StrUtil.isNotBlank(content)){
                    pendingTidList.addAll(Arrays.stream(content.split(",")).mapToLong(Convert::toLong).boxed().collect(Collectors.toList()));
                }
            }else{
                log.error("待处理过车文件:{}内容：{}不符合格式", FileUtil.getAbsolutePath(pendingTidListFile), content);
            }
        }else{
            FileUtil.touch(pendingTidListFile);
            log.info("已创建待处理tid文件：{}", FileUtil.getAbsolutePath(pendingTidListFile));
        }
        trainLogService = SpringContextHolder.getBean(ITrainLogService.class);
        daAlarmLogService = SpringContextHolder.getBean(IDaAlarmLogService.class);
        daAlarm360Service = SpringContextHolder.getBean(IDaAlarm360Service.class);
        checkItemService = SpringContextHolder.getBean(ICheckItemService.class);
        restTemplate = SpringContextHolder.getBean(RestTemplate.class);
    }

    /**
     * 读取要处理的一个过车记录
     * @return
     */
    private Optional<TrainLog> readTid(){
        String tidStr=null;
        try {
            tidStr=FileUtil.readUtf8String(GlobalHolder.PROPERTIES.getProperty("storeFile"));
        } catch (IORuntimeException e) {
            log.error("读取过车记录文件异常:{}", GlobalHolder.PROPERTIES.getProperty("storeFile"));
        }

        TrainLog trainLog = null;
        if(StrUtil.isBlank(tidStr) || !NumberUtil.isLong(tidStr)){ // 如果字符串是空串或者不是Long类型，则查找最新的来车记录
            if(log.isDebugEnabled()){
                log.warn("读取文件：{}的过车记录id非数字！tid:{}", GlobalHolder.PROPERTIES.getProperty("storeFile"), tidStr);
            }
            EntityWrapper<TrainLog> wrapper = new EntityWrapper<>();
            wrapper.ge("trance_status", COMPLETED_TRANCE_STATUS);
            wrapper.orderBy("trace_time", false);
            Page<TrainLog> page = new Page<>(1, 1);
            page = trainLogService.selectPage(page, wrapper);
            List<TrainLog> records = page.getRecords();
            if (records.isEmpty()) {
                log.info("找不到最新的已完成过车记录！！！");
                return Optional.empty();
            }else{
                trainLog = records.get(0);
                if(log.isDebugEnabled()){
                    log.debug("获取最新的已完成过车记录id：{}", trainLog.getId());
                }
                return Optional.ofNullable(trainLog);
            }
        }else{
            Long tid = NumberUtil.parseLong(tidStr);
            EntityWrapper<TrainLog> wrapper = new EntityWrapper<>();
            wrapper.gt("id",tid);
            wrapper.orderBy("id");
            Page<TrainLog> page = new Page<>(1, 1);
            page = trainLogService.selectPage(page, wrapper);
            List<TrainLog> trainLogs = page.getRecords();
            if(!trainLogs.isEmpty()){
                TrainLog one = trainLogs.get(0);
                if (one.getTranceStatus().intValue() >= COMPLETED_TRANCE_STATUS){   // 如果该记录是已完成的记录
                    trainLog=one;
                }else{  // 未完成的记录加入待处理队列中
                    addOneToPendingList(one.getId());
                    // 这里要保存以下指针，因为后续的调用不会保留指针
                    saveTrainLogId(one.getId());
                }
            }
        }
        return Optional.ofNullable(trainLog);
    }

    /**
     * 处理过车记录
     * @param trainLog
     * @return
     */
    private boolean handleTrainLog(TrainLog trainLog){
        if(trainLog==null){
            return true;
        }
        EnableCheckItemDTO enableCheckItemDTO = checkItemService.listEnableCkItemWithoutStation();
        List<CheckItem> to3CheckItemList = enableCheckItemDTO.getTo3CheckItemList();
        List<CheckItem> commonCheckItemList = enableCheckItemDTO.getCommonCheckItemList();
        List<AlarmVO> alarmVOS = new LinkedList<>();
//        if (CollUtil.isNotEmpty(to3CheckItemList)) {
//            List<String> ckItemStrs = CkIdentityUtil.checkItems2CkItemStrs(to3CheckItemList);
//            List<DaAlarm360Log> daAlarm360Logs = daAlarm360Service.listAlarmByTid(trainLog.getId(), ckItemStrs, null, null);
//        }

        if(CollUtil.isNotEmpty(commonCheckItemList)){
            List<String> ckItemStrs = CkIdentityUtil.checkItems2CkItemStrs(to3CheckItemList);
            List<DaAlarmLog> daAlarmLogList = daAlarmLogService.listAlarmByTid(trainLog.getId(), ckItemStrs, null, null, true, false);
            for (DaAlarmLog daAlarmLog : daAlarmLogList) {
                AlarmVO alarmVO = new AlarmVO();
                alarmVO.setTid(daAlarmLog.getTrainLogId());
                alarmVO.setAlarmId(daAlarmLog.getId());
                alarmVO.setTrainNo(trainLog.getTrainNo());
                alarmVO.setAlarmCode(StrUtil.format("{}_{}",daAlarmLog.getDeviceTypeId(),daAlarmLog.getCheckId()));
                alarmVO.setDeviceType(NannarNameHelper.getDeviceTypeName(daAlarmLog.getDeviceTypeId()));
                alarmVO.setCheckIdName(NannarNameHelper.getCheckIdName(daAlarmLog.getDeviceTypeId(),daAlarmLog.getCheckId()));
                alarmVO.setPartName(NannarNameHelper.getPartName(trainLog.getTrainNo(),daAlarmLog.getDeviceTypeId(),daAlarmLog.getCheckId(),daAlarmLog.getPartCode()));
                CheckItem checkItem = commonCheckItemList.stream().filter(e -> Objects.equals(e.getDeviceTypeId(), daAlarmLog.getDeviceTypeId()) && Objects.equals(e.getPointTypeId(), daAlarmLog.getCheckId()))
                        .findFirst().orElse(null);
                if(checkItem==null){
                    alarmVO.setDataType(1);
                }else{
                    Integer isTelemetry = checkItem.getIsTelemetry();
                    if(isTelemetry==null){
                        isTelemetry=1;
                    }
                    alarmVO.setDataType(isTelemetry);
                }
                if(alarmVO.getDataType().equals(1)){    // 数值数据
                    BigDecimal daAlarmValue = daAlarmLog.getDaAlarmValue();
                    if(daAlarmValue!=null){
                        alarmVO.setValue(daAlarmValue.toString());
                    }
                }else{  // 状态数据
                    BigDecimal daAlarmValue = daAlarmLog.getDaAlarmValue();
                    if(daAlarmValue!=null){
                        NormalEnum normalEnum = daAlarmValue.intValue() > 0 ? NormalEnum.ABNORMAL : NormalEnum.NORMAL;
                        alarmVO.setValue(normalEnum.getMessage());
                    }
                }
                alarmVO.setAlarmLevel(daAlarmLog.getDaAlarmLevel());
                alarmVO.setTraceTime(trainLog.getTraceTime());
                alarmVO.setStationName(NannarNameHelper.getStationName(trainLog.getStationId()));
                alarmVO.setDirection(NannarNameHelper.getDirectionName(trainLog.getDirection()));
                alarmVOS.add(alarmVO);
            }
        }

        JSONObject jsonObject = new JSONObject();
        jsonObject.put("source", "轨旁系统");
        jsonObject.put("data", alarmVOS);

        String pushAlarmUrl = GlobalHolder.PROPERTIES.getProperty("pushAlarmUrl");
        String reqJsonPara = JSONObject.toJSONString(jsonObject, SerializerFeature.WriteDateUseDateFormat, SerializerFeature.WriteMapNullValue, SerializerFeature.WriteNullStringAsEmpty,
                SerializerFeature.WriteNullListAsEmpty, SerializerFeature.DisableCircularReferenceDetect);
        log.info("提交告警数据：{}", reqJsonPara);
        ResponseData responseData=null;
        try {
             responseData = restTemplate.postForObject(URI.create(pushAlarmUrl), reqJsonPara, ResponseData.class);
        } catch (RestClientException e) {
            log.error("提交告警数据失败!",e);
            return false;
        }
        if(responseData==null){
            return false;
        }
        if(responseData.getSuccess()==null || !responseData.getSuccess()){
            return false;
        }
        return true;
    }

    /**
     * 保存处理过的过车记录id
     * @param tid
     * @return
     */
    private boolean saveTrainLogId(Long tid){
        if(tid==null){
            log.error("要保存的过车记录ID为null");
            return false;
        }
        FileWriter fileWriter = new FileWriter(GlobalHolder.PROPERTIES.getProperty("storeFile"), StandardCharsets.UTF_8);
        fileWriter.write(tid.toString());
        return true;
    }




    /**
     * 加入tid 序号到待处理队列
     * @param tid
     */
    private void addOneToPendingList(Long tid){
        synchronized (pendingTidList) {
            if(pendingTidList.size()>=TID_SLIDER_WINDOW_SIZE){ // 如果待处理队列长度大于或者等于滑动窗口大小
                log.error("待处理队列：{} 数量>=：{}",pendingTidList,TID_SLIDER_WINDOW_SIZE);
                ArrayList<Long> tidsToRemove = new ArrayList<>(pendingTidList.subList(0, pendingTidList.size() - TID_SLIDER_WINDOW_SIZE + 1));
                pendingTidList.removeAll(tidsToRemove);
                log.error("队列满，强制移除队列元素：{}", tidsToRemove);
            }
            pendingTidList.add(tid);
            log.info("待处理队列：{}", pendingTidList);
            String pendingTidsStr = CollUtil.join(pendingTidList, ",");
            FileUtil.writeUtf8String(pendingTidsStr, GlobalHolder.PROPERTIES.getProperty("pendingTidListFile"));
        }
    }



    /**
     * 处理待处理的队列
     */
    public void handlePendingList(){
        if(log.isDebugEnabled()){
            log.debug("处理待处理过车...");
        }
        ArrayList<Long> tmpList = null;
        synchronized (pendingTidList){
            tmpList=new ArrayList<>(pendingTidList);
        }
        if (tmpList.isEmpty()) {
            return;
        }
        List<Long> disposedTidList = new LinkedList<>();
        for (Long tid : tmpList) {
            TrainLog trainLog = trainLogService.selectById(tid);
            if(trainLog==null){
                log.error("根据过车id：{}查询不到过车！！！", tid);
                continue;
            }
            if (trainLog.getTranceStatus() < COMPLETED_TRANCE_STATUS) {
                continue;
            }
            log.info("处理待处理过车：{} ...", trainLog.getId());
            boolean flag = handleTrainLog(trainLog);
            if(flag){
                log.info("过车：{}数据推送成功！", trainLog.getId());
            }else{
                log.info("过车：{}数据推送失败！", trainLog.getId());
            }
            disposedTidList.add(tid);
        }
        // 移除已经处理过的过车
        if(!disposedTidList.isEmpty()){
            synchronized (pendingTidList){
                log.info("待处理队列：{} 移除元素：{}", pendingTidList, disposedTidList);
                pendingTidList.removeAll(disposedTidList);
                log.info("移除元素后,待处理队列：{}", pendingTidList);
                String pendingTidsStr = CollUtil.join(pendingTidList, ",");
                FileUtil.writeUtf8String(pendingTidsStr, GlobalHolder.PROPERTIES.getProperty("pendingTidListFile"));
            }
        }
    }
}
