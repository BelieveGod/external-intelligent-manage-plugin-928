package cn.nannar.plugin.vo;

import lombok.Data;

/**
 * @author LTJ
 * @date 2023/6/15
 */
@Data
public class RobotAlarmVO {
    private Long taskId;
    private Integer carriage;
    private String picPointSeqName;
    private String picPointSeq;
    private Integer arrIdx;
    private String description;
}
