package com.ioj.iojcodesandbox;


import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.*;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import com.ioj.iojcodesandbox.model.JudgeInfo;

import java.io.*;
import java.util.ArrayList;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.resource.ResourceUtil;
import com.ioj.iojcodesandbox.model.ExecuteCodeRequest;
import com.ioj.iojcodesandbox.model.ExecuteCodeResponse;
import com.ioj.iojcodesandbox.model.ExecuteMessage;
import com.ioj.iojcodesandbox.security.DefaultSecurityManager;
import com.ioj.iojcodesandbox.security.DenyAllSecurityManager;
import com.ioj.iojcodesandbox.security.SelfDefineSecurityManager;
import com.ioj.iojcodesandbox.utils.DockerUtils;
import com.ioj.iojcodesandbox.utils.ProcessUtils;
import org.springframework.util.StopWatch;

public class JavaDockerCodeSandBox implements CodeSandBox {

    public static final String GLOBAL_CODE_DIR_NAME = "tempCode";

    public static final String GLOBAL_JAVA_CODE_NAME = "Main.java";

    public static Boolean IS_FIRST_PULL_IMAGE = true;

    public static final long EXEC_TIMEOUT = 5000L;

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

        System.out.println("执行代码结果：" + executeCodeResponse);
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
            System.out.println("编译信息：" + executeMessage);
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
                    IS_FIRST_PULL_IMAGE =  Boolean.FALSE;
                    System.out.println("下载镜像：" + item.getStatus());
                    super.onNext(item);
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
        hostConfig.withMemory(100 * 1000 * 1000L);
        hostConfig.withMemorySwap(0L);
        hostConfig.withCpuCount(1L);
        CreateContainerResponse createContainerResponse = createContainerCmd
                .withHostConfig(hostConfig)
                .withNetworkDisabled(true) // 禁用网络
                .withReadonlyRootfs(true)
                .withAttachStdin(true)
                .withAttachStdout(true)
                .withAttachStderr(true)
                .withTty(true)
                .exec();
        String containerId = createContainerResponse.getId();

        // 启动
        dockerClient.startContainerCmd(containerId).exec();
        //  docker exec practical_goodall java -cp /app Main 1 3
        // 执行命令并获取结果
        List<ExecuteMessage> executeMessageList = new ArrayList<>();
        StopWatch stopWatch = new StopWatch();
        for (String inputArgs : inputList) {
            String[] inputArgsList = inputArgs.split(" ");
            String[] cmdArray = ArrayUtil.append(new String[]{"java", "-cp", "/app", "Main"}, inputArgsList);
            System.out.println("cmdArray: " + cmdArray);
            ExecCreateCmdResponse execCreateCmdResponse = dockerClient
                    .execCreateCmd(containerId)
                    .withAttachStderr(true)
                    .withAttachStdin(true)
                    .withAttachStdout(true)
                    .withCmd(cmdArray)
                    .exec();

            ExecuteMessage executeMessage = new ExecuteMessage();
            long execTime = 0L;
            final boolean[] timeout = {true};
            final String[] message = {null};
            final String[] errorMessage = {null};
            ExecStartResultCallback execStartResultCallback = new ExecStartResultCallback() {
                @Override
                public void onComplete() {
                    // 如果执行完成，则表示未超时
                    timeout[0] = false;
                    super.onComplete();
                }

                @Override
                public void onNext(Frame frame) {
                    StreamType streamType = frame.getStreamType();
                    if (StreamType.STDERR.equals(streamType)) {
                        errorMessage[0] = new String(frame.getPayload());
                        System.out.println("输出错误结果：" + errorMessage[0]);
                    } else {
                        message[0] = new String(frame.getPayload());
                        System.out.println("输出结果：" + message[0]);
                    }
                    super.onNext(frame);
                }
            };

            // 先启动监控，获取占用内存
            final long[] maxMemoryLimit = {0L};
            StatsCmd statsCmd = dockerClient.statsCmd(containerId);
            ResultCallback<Statistics> statisticsResultCallback = statsCmd.exec(new ResultCallback<Statistics>() {
                @Override
                public void onNext(Statistics statistics) {
                    System.out.println("内存使用：" + statistics.getMemoryStats().getUsage());
                    Long useMemory = statistics.getMemoryStats().getUsage();
                    maxMemoryLimit[0] = Math.max(maxMemoryLimit[0], useMemory);
                }

                @Override
                public void onStart(Closeable closeable) {

                }

                @Override
                public void onError(Throwable throwable) {

                }

                @Override
                public void onComplete() {

                }

                @Override
                public void close() throws IOException {

                }
            });
            statsCmd.exec(statisticsResultCallback);

            // 开始执行程序
            try {
                String execId = execCreateCmdResponse.getId();
                stopWatch.start();
                dockerClient
                        .execStartCmd(execId)
                        .exec(execStartResultCallback)
                        .awaitCompletion(EXEC_TIMEOUT, TimeUnit.MILLISECONDS);
                stopWatch.stop();
                execTime = stopWatch.getLastTaskTimeMillis();
            } catch (InterruptedException e) {
                System.out.println("执行程序异常");
                throw new RuntimeException(e);
            } finally {
                statsCmd.close();
            }

            executeMessage.setMessage(message[0]);
            executeMessage.setErrMessage(errorMessage[0]);
            executeMessage.setTime(execTime);
            executeMessage.setMemory(maxMemoryLimit[0]);
            executeMessageList.add(executeMessage);
        }

        // 4. 收集整理结果
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

        // 5. 文件清理
        if (userCodeFile.getParentFile() != null) {
            boolean del = FileUtil.del(userCodeParenPath);
            System.out.println("删除" + (del ? "成功" : "失败"));
        }

        return executeCodeResponse;
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

