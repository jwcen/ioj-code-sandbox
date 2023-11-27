package com.ioj.iojcodesandbox.model;

import lombok.Data;

@Data
public class ExecuteMessage {

    private Integer exitValue;

    private String Message;

    private String errMessage;

    private Long time;

    private Long memory;
}
