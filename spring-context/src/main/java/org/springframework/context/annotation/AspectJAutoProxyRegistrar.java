/*
 * Copyright 2002-2017 the original author or authors.
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

import org.springframework.aop.config.AopConfigUtils;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.type.AnnotationMetadata;

/**
 * Registers an {@link org.springframework.aop.aspectj.annotation.AnnotationAwareAspectJAutoProxyCreator
 * AnnotationAwareAspectJAutoProxyCreator} against the current {@link BeanDefinitionRegistry}
 * as appropriate based on a given @{@link EnableAspectJAutoProxy} annotation.
 *
 * @author Chris Beams
 * @author Juergen Hoeller
 * @since 3.1
 * @see EnableAspectJAutoProxy
 */
class AspectJAutoProxyRegistrar implements ImportBeanDefinitionRegistrar {

	/**
	 * Register, escalate, and configure the AspectJ auto proxy creator based on the value
	 * of the @{@link EnableAspectJAutoProxy#proxyTargetClass()} attribute on the importing
	 * {@code @Configuration} class.
	 * 注册、配置一个AnnotationAwareAspectJAutoProxyCreator类型的自动代理创建者
	 */
	@Override
	public void registerBeanDefinitions(
			AnnotationMetadata importingClassMetadata, BeanDefinitionRegistry registry) {
		/*
		 * 1 尝试注册或者升级一个名为"org.springframework.aop.config.internalAutoProxyCreator"
		 * 类型为AnnotationAwareAspectJAutoProxyCreator的自动代理创建者的bean定义
		 *
		 * 这个方法，我们在解析<aop:aspectj-autoproxy/>标签的源码中就见过了，这就是核心逻辑
		 */
		AopConfigUtils.registerAspectJAnnotationAutoProxyCreatorIfNecessary(registry);
		/*
		 * 2 解析@EnableAspectJAutoProxy注解的属性，配置自动代理创建者
		 */
		//获取@EnableAspectJAutoProxy注解的属性集合
		AnnotationAttributes enableAspectJAutoProxy =
				AnnotationConfigUtils.attributesFor(importingClassMetadata, EnableAspectJAutoProxy.class);
		if (enableAspectJAutoProxy != null) {
			//如果设置了proxyTargetClass属性为true
			if (enableAspectJAutoProxy.getBoolean("proxyTargetClass")) {
				//配置自动代理创建者强制使用CGLIB代理
				AopConfigUtils.forceAutoProxyCreatorToUseClassProxying(registry);
			}
			//如果设置了exposeProxy属性为true
			if (enableAspectJAutoProxy.getBoolean("exposeProxy")) {
				//配置自动代理创建者强制暴露代理对象
				AopConfigUtils.forceAutoProxyCreatorToExposeProxy(registry);
			}
		}
	}

}
