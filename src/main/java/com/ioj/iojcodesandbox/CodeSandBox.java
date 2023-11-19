package com.ioj.iojcodesandbox;


import com.ioj.iojcodesandbox.model.ExecuteCodeRequest;
import com.ioj.iojcodesandbox.model.ExecuteCodeResponse;

public interface CodeSandBox {

    ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest);
}
