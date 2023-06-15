package cn.nannar.plugin.vo;

import lombok.Data;

import java.util.Date;

/**
 * @author LTJ
 * @date 2023/6/14
 */
@Data
public class AlarmVO {
    private Long tid;
    private Long alarmId;
    private String trainNo;
    private String direction;
    private String stationName;
    private Date traceTime;
    private String partName;
    private String deviceType;
    private String checkIdName;
    private Integer dataType;
    private String value;
    private Integer alarmLevel;
    private String alarmCode;
}
