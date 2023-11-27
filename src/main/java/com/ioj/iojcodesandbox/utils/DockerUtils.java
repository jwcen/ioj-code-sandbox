package com.ioj.iojcodesandbox.utils;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;

import java.util.List;

public class DockerUtils {
    public static boolean existsContainer(DockerClient dockerClient, String image) {
        // 在循环迭代之前检查容器的存在性
        boolean containerExists = false;
        List<Container> containerList = dockerClient.listContainersCmd().exec();
        for (Container container : containerList) {
            if (container.getImage().equals(image)) {
                containerExists = true;
                break;
            }
        }

        return containerExists;
    }
}
