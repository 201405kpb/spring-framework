package com.kpb.aop.annotation.first;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

@Configuration
@ComponentScan("com.kpb.aop.annotation.first")
//Java配置开启AOP注解支持
@EnableAspectJAutoProxy
public class FirstAppConfig {
}
