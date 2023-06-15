package cn.nannar.plugin.bo;

import lombok.Data;

/**
 * @author LTJ
 * @date 2023/6/13
 */
@Data
public class ConfirmBO {
    private Integer taskId;
    private String picPointSeq;
    private Integer arrIdx;
    private Integer alarmStatus;
}
