/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.dromara.soul.client.springcloud.init;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.dromara.soul.client.core.disruptor.SoulClientRegisterEventPublisher;
import org.dromara.soul.client.springcloud.annotation.SoulSpringCloudClient;
import org.dromara.soul.register.client.api.SoulClientRegisterRepository;
import org.dromara.soul.register.common.config.SoulRegisterCenterConfig;
import org.dromara.soul.register.common.dto.MetaDataRegisterDTO;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.env.Environment;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Controller;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * The type Soul client bean post processor.
 *
 * @author xiaoyu(Myth)
 */
@Slf4j
public class SpringCloudClientBeanPostProcessor implements BeanPostProcessor {
    
    private SoulClientRegisterEventPublisher publisher = SoulClientRegisterEventPublisher.getInstance();
    
    private final ThreadPoolExecutor executorService;
    
    private final String contextPath;
    
    private final Boolean isFull;
    
    private final Environment env;
    
    /**
     * Instantiates a new Soul client bean post processor.
     *
     * @param config the soul spring cloud config
     * @param env    the env
     * @param soulClientRegisterRepository the soulClientRegisterRepository
     */
    public SpringCloudClientBeanPostProcessor(final SoulRegisterCenterConfig config, final Environment env, final SoulClientRegisterRepository soulClientRegisterRepository) {
        String registerType = config.getRegisterType();
        String serverLists = config.getServerLists();
        Properties props = config.getProps();
        String contextPath = props.getProperty("contextPath");
        String appName = env.getProperty("spring.application.name");
        if (StringUtils.isBlank(contextPath) || StringUtils.isBlank(registerType)
                || StringUtils.isBlank(serverLists) || StringUtils.isBlank(appName)) {
            String errorMsg = "spring cloud param must config the contextPath ,registerType , serverLists  and appName";
            log.error(errorMsg);
            throw new RuntimeException(errorMsg);
        }
        executorService = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        this.env = env;
        this.contextPath = contextPath;
        this.isFull = Boolean.parseBoolean(props.getProperty("isFull", "false"));
        publisher.start(soulClientRegisterRepository);
    }

    @Override
    public Object postProcessAfterInitialization(@NonNull final Object bean, @NonNull final String beanName) throws BeansException {
        if (isFull) {
            return bean;
        }
        Controller controller = AnnotationUtils.findAnnotation(bean.getClass(), Controller.class);
        RestController restController = AnnotationUtils.findAnnotation(bean.getClass(), RestController.class);
        RequestMapping requestMapping = AnnotationUtils.findAnnotation(bean.getClass(), RequestMapping.class);
        if (controller != null || restController != null || requestMapping != null) {
            String prePath = "";
            SoulSpringCloudClient clazzAnnotation = AnnotationUtils.findAnnotation(bean.getClass(), SoulSpringCloudClient.class);
            if (Objects.isNull(clazzAnnotation)) {
                return bean;
            }
            if (clazzAnnotation.path().indexOf("*") > 1) {
                String finalPrePath = prePath;
                executorService.execute(() -> publisher.publishEvent(buildMetaDataDTO(clazzAnnotation, finalPrePath)));
                return bean;
            }
            prePath = clazzAnnotation.path();
            final Method[] methods = ReflectionUtils.getUniqueDeclaredMethods(bean.getClass());
            for (Method method : methods) {
                SoulSpringCloudClient soulSpringCloudClient = AnnotationUtils.findAnnotation(method, SoulSpringCloudClient.class);
                if (Objects.nonNull(soulSpringCloudClient)) {
                    String finalPrePath = prePath;
                    executorService.execute(() -> publisher.publishEvent(buildMetaDataDTO(soulSpringCloudClient, finalPrePath)));
                }
            }
        }
        return bean;
    }
    
    private MetaDataRegisterDTO buildMetaDataDTO(final SoulSpringCloudClient soulSpringCloudClient, final String prePath) {
        String contextPath = this.contextPath;
        String appName = env.getProperty("spring.application.name");
        String path = contextPath + prePath + soulSpringCloudClient.path();
        String desc = soulSpringCloudClient.desc();
        String configRuleName = soulSpringCloudClient.ruleName();
        String ruleName = ("".equals(configRuleName)) ? path : configRuleName;
        return MetaDataRegisterDTO.builder()
                .contextPath(contextPath)
                .appName(appName)
                .path(path)
                .pathDesc(desc)
                .rpcType(soulSpringCloudClient.rpcType())
                .enabled(soulSpringCloudClient.enabled())
                .ruleName(ruleName)
                .build();
    }
}


