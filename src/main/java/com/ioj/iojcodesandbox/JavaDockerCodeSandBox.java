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
import org.springframework.stereotype.Component;
import org.springframework.util.StopWatch;

/**
 * Java 代码沙箱（模版方法实现）
 */
@Component
public class JavaDockerCodeSandBox extends JavaCodeSandBoxTemplate {

    public static Boolean IS_FIRST_PULL_IMAGE = true;

    public static final long EXEC_TIMEOUT = 5000L;

    public static void main(String[] args) {
        JavaDockerCodeSandBox javaDockerCodeSandBox = new JavaDockerCodeSandBox();
        ExecuteCodeRequest executeCodeRequest = new ExecuteCodeRequest();
        executeCodeRequest.setInputList(Arrays.asList("1 2", "1 3"));
        String code = ResourceUtil.readStr("testCode/simpleComputeArgs/Main.java", StandardCharsets.UTF_8);
        executeCodeRequest.setCode(code);
        executeCodeRequest.setLanguage("Java");

        ExecuteCodeResponse executeCodeResponse = javaDockerCodeSandBox.executeCode(executeCodeRequest);

        System.out.println("执行代码结果：" + executeCodeResponse);
    }

    /**
     * 3. 创建容器，并执行代码
     * @param userCodeFile -
     * @param inputList -
     * @return
     */
    @Override
    public List<ExecuteMessage> runAndGetResult(File userCodeFile, List<String> inputList) {
        DockerClient dockerClient = DockerClientBuilder.getInstance().build();
        // pull image
        String image = "openjdk:8-alpine";
        if (IS_FIRST_PULL_IMAGE) {
            PullImageCmd pullImageCmd = dockerClient.pullImageCmd(image);
            PullImageResultCallback pullImageResultCallback = new PullImageResultCallback() {
                @Override
                public void onNext(PullResponseItem item) {
                    IS_FIRST_PULL_IMAGE = Boolean.FALSE;
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
        String userCodeParenPath = userCodeFile.getParentFile().getAbsolutePath();
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

        return executeMessageList;
    }
}

