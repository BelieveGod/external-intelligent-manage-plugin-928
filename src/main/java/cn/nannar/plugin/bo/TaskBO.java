package cn.nannar.plugin.bo;

import lombok.Data;

import java.util.List;

/**
 * @author LTJ
 * @date 2023/6/13
 */
@Data
public class TaskBO {
    private List<TaskItem> task;

    @Data
    public static class TaskItem{
        private Integer robotId;
        private Integer laneId;
        private String trainNo;
        private Integer direction;
    }

}
