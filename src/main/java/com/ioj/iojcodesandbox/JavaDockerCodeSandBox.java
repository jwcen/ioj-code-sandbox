package com.ioj.iojcodesandbox;


import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.PullImageCmd;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.PullResponseItem;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.core.DockerClientBuilder;
import com.ioj.iojcodesandbox.model.JudgeInfo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.resource.ResourceUtil;
import com.ioj.iojcodesandbox.model.ExecuteCodeRequest;
import com.ioj.iojcodesandbox.model.ExecuteCodeResponse;
import com.ioj.iojcodesandbox.model.ExecuteMessage;
import com.ioj.iojcodesandbox.security.DefaultSecurityManager;
import com.ioj.iojcodesandbox.security.DenyAllSecurityManager;
import com.ioj.iojcodesandbox.security.SelfDefineSecurityManager;
import com.ioj.iojcodesandbox.utils.ProcessUtils;
import org.springframework.util.StopWatch;

public class JavaDockerCodeSandBox implements CodeSandBox {

    public static final String GLOBAL_CODE_DIR_NAME = "tempCode";

    public static final String GLOBAL_JAVA_CODE_NAME = "Main.java";

    public static final Boolean IS_FIRST_PULL_IMAGE = true;

    public static void main(String[] args) {
        JavaDockerCodeSandBox javaDockerCodeSandBox = new JavaDockerCodeSandBox();
        ExecuteCodeRequest executeCodeRequest = new ExecuteCodeRequest();
        executeCodeRequest.setInputList(Arrays.asList("1 2", "1 3"));
        String code = ResourceUtil.readStr("testCode/simpleComputeArgs/Main.java", StandardCharsets.UTF_8);
//        String code = ResourceUtil.readStr("testCode/unsafeCode/SleepError.java", StandardCharsets.UTF_8);
//        String code = ResourceUtil.readStr("testCode/unsafeCode/MemoryError.java", StandardCharsets.UTF_8);
//        String code = ResourceUtil.readStr("testCode/unsafeCode/ReadFileError.java", StandardCharsets.UTF_8);
//        String code = ResourceUtil.readStr("testCode/unsafeCode/WriteFileError.java", StandardCharsets.UTF_8);
//        String code = ResourceUtil.readStr("testCode/unsafeCode/RunFileError.java", StandardCharsets.UTF_8);
        executeCodeRequest.setCode(code);
        executeCodeRequest.setLanguage("Java");

        ExecuteCodeResponse executeCodeResponse = javaDockerCodeSandBox.executeCode(executeCodeRequest);

        System.out.println(executeCodeResponse);
    }

    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest) {
//        System.setSecurityManager(new DefaultSecurityManager());
//        System.setSecurityManager(new DenyAllSecurityManager());
//        System.setSecurityManager(new SelfDefineSecurityManager());

        List<String> inputList = executeCodeRequest.getInputList();
        String code = executeCodeRequest.getCode();

        // 1. 将用户代码保存为文件
        String userDir = System.getProperty("user.dir");
        String globalCodePathName = userDir + File.separator + GLOBAL_CODE_DIR_NAME;
        // 判断全局代码目录是否存在
        if (!FileUtil.exist(globalCodePathName)) {
            FileUtil.mkdir(globalCodePathName);
        }

        // 将用户的代码隔离存放到一个目录下
        String userCodeParenPath = globalCodePathName + File.separator + UUID.randomUUID();
        String userCodePath = userCodeParenPath + File.separator + GLOBAL_JAVA_CODE_NAME;
        File userCodeFile = FileUtil.writeString(code, userCodePath, StandardCharsets.UTF_8);

        // 2. javac 编译代码，得到字节码class文件
        String compileCmd = String.format("javac -encoding utf-8 %s", userCodeFile.getAbsolutePath());
        try {
            Process compileProcess = Runtime.getRuntime().exec(compileCmd);
            ExecuteMessage executeMessage = ProcessUtils.runAndGetMessage(compileProcess, "编译");
            System.out.println(executeMessage);
        } catch (IOException e) {
            return getErrorResponse(e);
        }

        // 3. 创建容器，将文件复制到容器内
        DockerClient dockerClient = DockerClientBuilder.getInstance().build();
        // pull image
        String image = "openjdk:8-alpine";
        if (IS_FIRST_PULL_IMAGE) {
            PullImageCmd pullImageCmd = dockerClient.pullImageCmd(image);
            PullImageResultCallback pullImageResultCallback = new PullImageResultCallback() {
                @Override
                public void onNext(PullResponseItem item) {
                    super.onNext(item);
                    System.out.println("下载镜像：" + item.getStatus());
                }
            };
            try {
                pullImageCmd
                        .exec(pullImageResultCallback)
                        .awaitCompletion();
            } catch (InterruptedException e) {
                System.out.println("拉取镜像异常");
                throw new RuntimeException(e);
            }
        }
        System.out.println("下载完成");

        // 创建容器
        CreateContainerCmd createContainerCmd = dockerClient.createContainerCmd(image);
        // 挂载容器目录
        HostConfig hostConfig = new HostConfig();
        hostConfig.setBinds(new Bind(userCodeParenPath, new Volume("/app")));
        hostConfig.withMemory(100*1000*1000L);
        hostConfig.withCpuCount(1L);
        CreateContainerResponse createContainerResponse = createContainerCmd
                .withHostConfig(hostConfig)
                .withAttachStdin(true)
                .withAttachStdout(true)
                .withAttachStderr(true)
                .withTty(true)
                .exec();
        String containerId = createContainerResponse.getId();

        // 启动
        dockerClient.startContainerCmd(containerId).exec();

        return new ExecuteCodeResponse();
    }

    /**
     * 获取错误相应
     *
     * @param e
     * @return
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

