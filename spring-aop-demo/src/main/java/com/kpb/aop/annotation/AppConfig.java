package com.kpb.aop.annotation;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

@Configuration
@ComponentScan("com.kpb.aop.annotation")
//Java配置开启AOP注解支持
@EnableAspectJAutoProxy
public class AppConfig {
}
