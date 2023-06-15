package cn.nannar.plugin;

import cn.nannar.plugin.bo.TaskBO;
import cn.nannar.plugin.controller.External928Controller;
import cn.nannar.plugin.ctx.GlobalHolder;
import cn.nannar.plugin.jms.RobotListenner;
import cn.nannar.plugin.task.UploadTask;
import cn.stylefeng.roses.core.util.SpringContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jms.listener.DefaultMessageListenerContainer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import javax.jms.ConnectionFactory;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Properties;

/**
 * @author LTJ
 * @date 2022/9/30
 */
@Slf4j
public class Eimp928Plugin {

    private String cron;
    private String cron2;


    private String mileageSyncTaskFile;

    private String getTokenUrl;

    private String getMileageUrl;

    private String appid;

    private String secret;

    private volatile boolean isAdd;


    public Eimp928Plugin() {
        ClassLoader classLoader = this.getClass().getClassLoader();
        log.info("plugin:{} classloader is :{}", this.getClass().getSimpleName(), classLoader.toString());
        InputStream resourceAsStream = classLoader.getResourceAsStream("plugin.properties");
        Properties properties = new Properties();
        try {
            properties.load(resourceAsStream);
            resourceAsStream.close();
        } catch (IOException e) {
            log.error("里程数插件初始化失败", e);
            throw new RuntimeException(e);
        }
        GlobalHolder.PROPERTIES=properties;
        cron = properties.getProperty("cron");
        cron2 = properties.getProperty("cron2");
        isAdd = false;
    }

    public void runplugin() {
        synchronized (this) {
            if (!isAdd) {

                ConfigurableApplicationContext ctx = (ConfigurableApplicationContext) SpringContextHolder.getApplicationContext();
                DefaultListableBeanFactory beanFactory = (DefaultListableBeanFactory) ctx.getBeanFactory();
                /* begin ==========active MQ 注册============= */
//                BeanDefinitionBuilder bdbuilder = BeanDefinitionBuilder.genericBeanDefinition(RobotListenner.class);
//                beanFactory.registerBeanDefinition("robotListenner", bdbuilder.getRawBeanDefinition());
                ConnectionFactory connectionFactory = SpringContextHolder.getBean(ConnectionFactory.class);
                DefaultMessageListenerContainer defaultMessageListenerContainer = new DefaultMessageListenerContainer();
                defaultMessageListenerContainer.setPubSubDomain(true);
                defaultMessageListenerContainer.setMessageListener(new RobotListenner());
                defaultMessageListenerContainer.setDestinationName(GlobalHolder.PROPERTIES.getProperty("robotDestination"));
                defaultMessageListenerContainer.setConnectionFactory(connectionFactory);
                defaultMessageListenerContainer.afterPropertiesSet();
                defaultMessageListenerContainer.start();
                /* end ============active MQ 注册============ */
                /* begin ==========控制器注册============= */
                BeanDefinitionBuilder beanDefinitionBuilder = BeanDefinitionBuilder.genericBeanDefinition(External928Controller.class);
                beanFactory.registerBeanDefinition("external928Controller", beanDefinitionBuilder.getRawBeanDefinition());
                External928Controller external928Controller =  SpringContextHolder.getBean("external928Controller");
                RequestMappingHandlerMapping requestMappingHandlerMapping = SpringContextHolder.getBean(RequestMappingHandlerMapping.class);
                Method listDeviceStatus = null;
                Method getAlarmCode = null;
                Method createTask = null;
                Method confirm = null;
                try {
                    listDeviceStatus = External928Controller.class.getMethod("listDeviceStatus");
                    getAlarmCode = External928Controller.class.getMethod("getAlarmCode");
                    createTask = External928Controller.class.getMethod("createTask", TaskBO.class);
                    confirm = External928Controller.class.getMethod("confirm",List.class);
                } catch (NoSuchMethodException e) {
                    e.printStackTrace();
                }
                RequestMappingInfo mappingInfo = RequestMappingInfo.paths("/api/external/listDeviceStatus").build();
                RequestMappingInfo mappingInfo2 = RequestMappingInfo.paths("/api/external/getAlarmCode").build();
                RequestMappingInfo mappingInfo3 = RequestMappingInfo.paths("/api/external/robot/createTask").build();
                RequestMappingInfo mappingInfo4 = RequestMappingInfo.paths("/api/external/robot/confirm").build();
                requestMappingHandlerMapping.registerMapping(mappingInfo, external928Controller, listDeviceStatus);
                requestMappingHandlerMapping.registerMapping(mappingInfo2, external928Controller, getAlarmCode);
                requestMappingHandlerMapping.registerMapping(mappingInfo3, external928Controller, createTask);
                requestMappingHandlerMapping.registerMapping(mappingInfo4, external928Controller, confirm);
                /* end ============控制器注册============ */
                /* begin ==========开启定时任务线程============= */
                ThreadPoolTaskScheduler threadPoolTaskScheduler = SpringContextHolder.getBean(ThreadPoolTaskScheduler.class);
                UploadTask uploadTask = new UploadTask();
                try {
                    uploadTask.init();
                    threadPoolTaskScheduler.schedule(uploadTask::upload, new CronTrigger(cron));
                    threadPoolTaskScheduler.schedule(uploadTask::handlePendingList, new CronTrigger(cron2));
                    log.info("928智能运维对接插件的定时任务初始化成功");
                } catch (Exception e) {
                    log.error("928智能运维对接插件的定时任务初始化失败", e);
                }

                /* end ============开启定时任务线程============ */
                isAdd = true;
                log.info("已经注册了928外部智能运维插件");
            }
        }
    }


}
