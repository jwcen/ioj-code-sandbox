package com.ioj.iojcodesandbox.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.*;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.PullResponseItem;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.core.command.LogContainerResultCallback;

import java.util.List;

public class DockerDemo {
    public static void main(String[] args) throws InterruptedException {
        // 默认的 docker client
        DockerClient dockerClient = DockerClientBuilder.getInstance().build();
//        PingCmd pingCmd = dockerClient.pingCmd();
//        pingCmd.exec();

        // 下载镜像
        String image = "hello-world";
//        PullImageCmd pullImageCmd = dockerClient.pullImageCmd(image);
//        PullImageResultCallback pullImageResultCallback = new PullImageResultCallback() {
//            @Override
//            public void onNext(PullResponseItem item) {
//                System.out.println("下载镜像：" + item.getStatus());
//                super.onNext(item);
//            }
//        };
//        pullImageCmd
//                .exec(pullImageResultCallback)
//                .awaitCompletion();
//        System.out.println("下载完成");

        // 创建容器
        CreateContainerCmd containerCmd = dockerClient.createContainerCmd(image);
        CreateContainerResponse createContainerResponse = containerCmd
                .withCmd("echo", "Hello World!")
                .exec();
//        System.out.println("创建容器：" + createContainerResponse);

        // 查看容器状态
        ListContainersCmd listContainersCmd = dockerClient.listContainersCmd();
        List<Container> containerList = listContainersCmd.withShowAll(true).exec();
        System.out.println("查看容器状态");
        for (Container container : containerList) {
            System.out.println(container);
        }

        // 执行
        String containerId = createContainerResponse.getId();
        dockerClient.startContainerCmd(containerId);

        Thread.sleep(5000L);
        // 查看日志
        LogContainerResultCallback logContainerResultCallback = new LogContainerResultCallback() {
            @Override
            public void onNext(Frame item) {
                System.out.println(item.getStreamType());
                System.out.println("日志：" + new String(item.getPayload()));
                super.onNext(item);
            }
        };

        // 阻塞等待日志输出
        dockerClient.logContainerCmd(containerId)
                .withStdErr(true)
                .withStdOut(true)
                .exec(logContainerResultCallback)
                .awaitCompletion();

        // 删除容器、镜像
        dockerClient.removeContainerCmd(containerId).withForce(true).exec();
        dockerClient.removeImageCmd(image).exec();
    }
}
