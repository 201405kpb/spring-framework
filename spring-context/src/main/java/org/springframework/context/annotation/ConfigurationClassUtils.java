/*
 * Copyright 2002-2023 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.context.annotation;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.aop.framework.AopInfrastructureBean;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.cglib.proxy.Enhancer;
import org.springframework.context.event.EventListenerFactory;
import org.springframework.core.Conventions;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.Order;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

/**
 * Utilities for identifying and configuring {@link Configuration} classes.
 *
 * @author Chris Beams
 * @author Juergen Hoeller
 * @author Sam Brannen
 * @author Stephane Nicoll
 * @since 6.0
 */
public abstract class ConfigurationClassUtils {

	/**
	 * 如果当前bean定义的类上具有@Configuration注解，或者以@Configuration注解为元注解的注解（派生注解）
	 * 那么表示一个配置类，并且设置属性 CONFIGURATION_CLASS_ATTRIBUTE = full
	 */
	static final String CONFIGURATION_CLASS_FULL = "full";

	/**
	 * 如果当前bean定义的类上具有@Bean,@Component,@ComponentScan,@Import,@ImportResource注解之一，或者以这些注解为元注解的注解（派生注解）
	 * 那么表示一个配置类，并且设置属性 CONFIGURATION_CLASS_ATTRIBUTE = lite
	 */
	static final String CONFIGURATION_CLASS_LITE = "lite";

	/**
	 * 属性key
	 * org.springframework.context.annotation.ConfigurationClassPostProcessor.configurationClass
	 */
	static final String CONFIGURATION_CLASS_ATTRIBUTE =
			Conventions.getQualifiedAttributeName(ConfigurationClassPostProcessor.class, "configurationClass");

	/**
	 * 属性key
	 * org.springframework.context.annotation.ConfigurationClassPostProcessor.order
	 * 如果当前bean定义的类是一个配置类，并且具有@Order注解，那么会设置属性 ORDER_ATTRIBUTE = order值
	 */
	static final String ORDER_ATTRIBUTE =
			Conventions.getQualifiedAttributeName(ConfigurationClassPostProcessor.class, "order");


	private static final Log logger = LogFactory.getLog(ConfigurationClassUtils.class);

	private static final Set<String> candidateIndicators = Set.of(
			Component.class.getName(),
			ComponentScan.class.getName(),
			Import.class.getName(),
			ImportResource.class.getName());


	/**
	 * Initialize a configuration class proxy for the specified class.
	 *
	 * @param userClass the configuration class to initialize
	 */
	@SuppressWarnings("unused") // Used by AOT-optimized generated code
	public static Class<?> initializeConfigurationClass(Class<?> userClass) {
		Class<?> configurationClass = new ConfigurationClassEnhancer().enhance(userClass, null);
		Enhancer.registerStaticCallbacks(configurationClass, ConfigurationClassEnhancer.CALLBACKS);
		return configurationClass;
	}


	/**
	 * Check whether the given bean definition is a candidate for a configuration class
	 * (or a nested component class declared within a configuration/component class,
	 * to be auto-registered as well), and mark it accordingly.
	 *
	 * 检查给定的bean定义是否是配置类（configuration或component类中声明的嵌套组件类，也要自动注册）的候选项，并相应地标记它。
	 *
	 * @param beanDef               the bean definition to check 要检查的bean定义
	 * @param metadataReaderFactory the current factory in use by the caller 调用方正在使用的当前beanFactory工厂
	 * @return whether the candidate qualifies as (any kind of) configuration class 是否是配置类，true 是 false 否
	 */
	static boolean checkConfigurationClassCandidate(
			BeanDefinition beanDef, MetadataReaderFactory metadataReaderFactory) {
		// 获取组件的全限定类名
		String className = beanDef.getBeanClassName();
		if (className == null || beanDef.getFactoryMethodName() != null) {
			return false;
		}

		// 通过注解注入的db都是 AnnotatedGenericBeanDefinition,实现了AnnotatedBeanDefinition
		// spring 内部的db都是 RootBeanDefinition 实现了AbstractBeanDefinition
		// 此处主要用于判断是否归属于 AnnotatedGenericBeanDefinition
		AnnotationMetadata metadata;
		if (beanDef instanceof AnnotatedBeanDefinition annotatedBd &&
				className.equals(annotatedBd.getMetadata().getClassName())) {
			// Can reuse the pre-parsed metadata from the given BeanDefinition...
			// 从当前的bean 定义信息中获取元数据信息
			metadata = annotatedBd.getMetadata();
		}
		// 判断 是否为spring 中默认的 BeanDefinition
		else if (beanDef instanceof AbstractBeanDefinition abstractBd && abstractBd.hasBeanClass()) {
			// Check already loaded Class if present...
			// since we possibly can't even load the class file for this Class.
			// 获取当前bean对象的class对象
			Class<?> beanClass = abstractBd.getBeanClass();
			// 判断是否是指定的子类
			if (BeanFactoryPostProcessor.class.isAssignableFrom(beanClass) ||
					BeanPostProcessor.class.isAssignableFrom(beanClass) ||
					AopInfrastructureBean.class.isAssignableFrom(beanClass) ||
					EventListenerFactory.class.isAssignableFrom(beanClass)) {
				return false;
			}
			// 根据 beanClass 生成 AnnotationMetadata 对象
			metadata = AnnotationMetadata.introspect(beanClass);
		} else {
			try {
				MetadataReader metadataReader = metadataReaderFactory.getMetadataReader(className);
				metadata = metadataReader.getAnnotationMetadata();
			} catch (IOException ex) {
				if (logger.isDebugEnabled()) {
					logger.debug("Could not find class file for introspecting configuration annotations: " +
							className, ex);
				}
				return false;
			}
		}
		// 获取组件类的@Configuration注解属性，如果组件类上不存在@Configuration注解，则返回空
		Map<String, Object> config = metadata.getAnnotationAttributes(Configuration.class.getName());
		// 如果存在，并且proxyBeanMethods属性的值为true，则标注当前配置类为full，即需要代理增强，为一个代理类
		if (config != null && !Boolean.FALSE.equals(config.get("proxyBeanMethods"))) {
			beanDef.setAttribute(CONFIGURATION_CLASS_ATTRIBUTE, CONFIGURATION_CLASS_FULL);
		}
		// 如果存在，并且isConfigurationCandidate返回true，则标注当前配置类为lite，即不需要代理增强，为一个普通类
		else if (config != null || isConfigurationCandidate(metadata)) {
			beanDef.setAttribute(CONFIGURATION_CLASS_ATTRIBUTE, CONFIGURATION_CLASS_LITE);
		}
		// 组件类不存在@Configuration注解，直接返回false
		else {
			return false;
		}

		// It's a full or lite configuration candidate... Let's determine the order value, if any.
		// 获取配置类的排序顺序，设置到组件定义属性中
		Integer order = getOrder(metadata);
		if (order != null) {
			beanDef.setAttribute(ORDER_ATTRIBUTE, order);
		}

		return true;
	}

	/**
	 * Check the given metadata for a configuration class candidate
	 * (or nested component class declared within a configuration/component class).
	 * 确定是否是配置类
	 *
	 * @param metadata the metadata of the annotated class
	 * @return {@code true} if the given class is to be registered for
	 * configuration class processing; {@code false} otherwise
	 */
	static boolean isConfigurationCandidate(AnnotationMetadata metadata) {
		// Do not consider an interface or an annotation...
		// 如果配置类为一个接口，则不处理
		if (metadata.isInterface()) {
			return false;
		}

		// Any of the typical annotations found?
		//判断配置类是否存在（@Component，ComponentScan，@Import，ImportResource）注解
		//如果存在上述任意一个注解，则返回true
		for (String indicator : candidateIndicators) {
			if (metadata.isAnnotated(indicator)) {
				return true;
			}
		}

		// Finally, let's look for @Bean methods...
		// 如果上述四个注解都不存在，则判断配置类中是否存在被@Bean标注的方法
		return hasBeanMethods(metadata);
	}

	static boolean hasBeanMethods(AnnotationMetadata metadata) {
		try {
			return metadata.hasAnnotatedMethods(Bean.class.getName());
		} catch (Throwable ex) {
			if (logger.isDebugEnabled()) {
				logger.debug("Failed to introspect @Bean methods on class [" + metadata.getClassName() + "]: " + ex);
			}
			return false;
		}
	}

	/**
	 * Determine the order for the given configuration class metadata.
	 *
	 * @param metadata the metadata of the annotated class
	 * @return the {@code @Order} annotation value on the configuration class,
	 * or {@code Ordered.LOWEST_PRECEDENCE} if none declared
	 * @since 5.0
	 */
	@Nullable
	public static Integer getOrder(AnnotationMetadata metadata) {
		Map<String, Object> orderAttributes = metadata.getAnnotationAttributes(Order.class.getName());
		return (orderAttributes != null ? ((Integer) orderAttributes.get(AnnotationUtils.VALUE)) : null);
	}

	/**
	 * Determine the order for the given configuration class bean definition,
	 * as set by {@link #checkConfigurationClassCandidate}.
	 *
	 * @param beanDef the bean definition to check
	 * @return the {@link Order @Order} annotation value on the configuration class,
	 * or {@link Ordered#LOWEST_PRECEDENCE} if none declared
	 * @since 4.2
	 */
	public static int getOrder(BeanDefinition beanDef) {
		Integer order = (Integer) beanDef.getAttribute(ORDER_ATTRIBUTE);
		return (order != null ? order : Ordered.LOWEST_PRECEDENCE);
	}

}
