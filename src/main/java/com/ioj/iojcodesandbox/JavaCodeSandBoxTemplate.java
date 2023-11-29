package com.ioj.iojcodesandbox;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.ioj.iojcodesandbox.model.ExecuteCodeRequest;
import com.ioj.iojcodesandbox.model.ExecuteCodeResponse;
import com.ioj.iojcodesandbox.model.ExecuteMessage;
import com.ioj.iojcodesandbox.model.JudgeInfo;
import com.ioj.iojcodesandbox.utils.ProcessUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
public abstract class JavaCodeSandBoxTemplate implements CodeSandBox {

    public static final String GLOBAL_CODE_DIR_NAME = "tempCode";

    public static final String GLOBAL_JAVA_CODE_NAME = "Main.java";

    /**
     * 超时时间设为5s
     */
    public static final long TIMEOUT = 5000L;

    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest) {
        List<String> inputList = executeCodeRequest.getInputList();
        String code = executeCodeRequest.getCode();

        // 1. 将用户代码保存为文件
        File userCodeFile = saveCodeToFile(code);

        // 2. javac 编译代码，得到字节码class文件
        ExecuteMessage compileFileExecMessage = compileToClassFile(userCodeFile);
        log.info(compileFileExecMessage.toString());

        // 3. java 执行代码，获取运行结果
        List<ExecuteMessage> executeMessageList = runAndGetResult(userCodeFile, inputList);
        log.info("运行结果：{}", executeMessageList);

        // 4. 收集整理结果
        ExecuteCodeResponse executeCodeResponse = getExecCodeResponse(executeMessageList);

        // 5. 文件清理
        boolean b = deleteFile(userCodeFile);
        if (!b)
            log.info("delete file error, userCodePath = {}", userCodeFile.getAbsolutePath());

        return executeCodeResponse;
    }

    /**
     * 1. 把用户的代码保存为文件
     * @param code  user code
     * @return File
     */
    public File saveCodeToFile(String code) {
        String userDir = System.getProperty("user.dir");
        String globalCodePathName = userDir + File.separator + GLOBAL_CODE_DIR_NAME;
        // 判断全局代码目录是否存在
        if (!FileUtil.exist(globalCodePathName)) {
            FileUtil.mkdir(globalCodePathName);
        }

        // 将用户的代码隔离存放到一个目录下
        String userCodeParenPath = globalCodePathName + File.separator + UUID.randomUUID();
        String userCodePath = userCodeParenPath + File.separator + GLOBAL_JAVA_CODE_NAME;

        return FileUtil.writeString(code, userCodePath, StandardCharsets.UTF_8);
    }

    /**
     * 2. javac 编译代码，得到字节码class文件
     * @param userCodeFile 用户代码文件
     * @return ExecuteMessage
     */
    public ExecuteMessage compileToClassFile(File userCodeFile) {
        String compileCmd = String.format("javac -encoding utf-8 %s", userCodeFile.getAbsolutePath());
        try {
            Process compileProcess = Runtime.getRuntime().exec(compileCmd);
            ExecuteMessage executeMessage = ProcessUtils.runAndGetMessage(compileProcess, "编译");
            if (executeMessage.getExitValue() != 0) {
                throw new RuntimeException("编译错误");
            }
            return executeMessage;
        } catch (IOException e) {
            // return getErrorResponse(e);
            throw new RuntimeException(e);
        }
    }

    /**
     * 3. java 执行代码，获取运行结果
     * @param userCodeFile -
     * @param inputList -
     * @return List<ExecuteMessage>
     */
    public List<ExecuteMessage> runAndGetResult(File userCodeFile, List<String> inputList) {
        List<ExecuteMessage> executeMessageList = new ArrayList<>();
        String userCodeParenPath = userCodeFile.getParentFile().getAbsolutePath();

        for (String inputArgs : inputList) {
//            String runCmd = String.format("java -Xmx256 -Dfile.encoding=UTF-8 -cp %s;%s -Djava.security.manager=MySecurityManager Main", userCodeParenPath, inputArgs);
            String runCmd = String.format("java  -Dfile.encoding=UTF-8 -cp %s Main %s", userCodeParenPath, inputArgs);
            try {
                Process runProcess = Runtime.getRuntime().exec(runCmd);
                new Thread(() -> {
                    try {
                        Thread.sleep(TIMEOUT);
                        runProcess.destroy();
                        System.out.println("程序运行超时，已中断");
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }).start();
                ExecuteMessage executeMessage = ProcessUtils.runAndGetMessage(runProcess, "运行");
                executeMessageList.add(executeMessage);
                log.info(executeMessage.toString());
            } catch (Exception e) {
                throw new RuntimeException("程序执行错误", e);
            }
        }

        return executeMessageList;
    }

    /**
     * 4. 收集响应结果
     * @param executeMessageList - List<ExecuteMessage>
     * @return ExecuteCodeResponse
     */
    public ExecuteCodeResponse getExecCodeResponse(List<ExecuteMessage> executeMessageList) {
        ExecuteCodeResponse executeCodeResponse = new ExecuteCodeResponse();

        List<String> outputList = new ArrayList<>();
        long maxTime = 0;

        for (ExecuteMessage executeMessage : executeMessageList) {
            String errMessage = executeMessage.getErrMessage();
            // 执行中存在错误
            if (StrUtil.isNotBlank(errMessage)) {
                executeCodeResponse.setMessage(errMessage);
                executeCodeResponse.setStatus(3);
                break;
            }
            outputList.add(executeMessage.getMessage());
            Long time = executeMessage.getTime();
            if (time != null) {
                maxTime = Math.max(time, maxTime);
            }
        }

        // 正常运行完成
        if (outputList.size() == executeMessageList.size()) {
            executeCodeResponse.setStatus(1);
        }
        executeCodeResponse.setOutputList(outputList);
        JudgeInfo judgeInfo = new JudgeInfo();
        judgeInfo.setTime(maxTime);
        // judgeInfo.setMemory(0L);
        executeCodeResponse.setJudgeInfo(judgeInfo);

        return executeCodeResponse;
    }

    /**
     * 5. 文件清理
     * @param userCodeFile -
     * @return boolean
     */
    public boolean deleteFile(File userCodeFile) {
        if (userCodeFile.getParentFile() != null) {
            String userCodeParenPath = userCodeFile.getParentFile().getAbsolutePath();
            boolean del = FileUtil.del(userCodeParenPath);
            System.out.println("删除" + (del ? "成功" : "失败"));
            return del;
        }

        return true;
    }

    /**
     * 6.获取错误相应
     * @param e -
     * @return ExecuteCodeResponse
     */
    private ExecuteCodeResponse getErrorResponse(Throwable e) {
        ExecuteCodeResponse executeCodeResponse = new ExecuteCodeResponse();
        executeCodeResponse.setOutputList(new ArrayList<>());
        executeCodeResponse.setMessage(e.getMessage());
        // 代码沙箱执行错误
        executeCodeResponse.setStatus(2);
        executeCodeResponse.setJudgeInfo(new JudgeInfo());

        return executeCodeResponse;
    }
}
