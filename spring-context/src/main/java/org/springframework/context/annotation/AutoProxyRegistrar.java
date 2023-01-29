/*
 * Copyright 2002-2020 the original author or authors.
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
import org.springframework.aop.config.AopConfigUtils;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.type.AnnotationMetadata;

import java.util.Set;

/**
 * Registers an auto proxy creator against the current {@link BeanDefinitionRegistry}
 * as appropriate based on an {@code @Enable*} annotation having {@code mode} and
 * {@code proxyTargetClass} attributes set to the correct values.
 *
 * @author Chris Beams
 * @since 3.1
 * @see org.springframework.cache.annotation.EnableCaching
 * @see org.springframework.transaction.annotation.EnableTransactionManagement
 */
public class AutoProxyRegistrar implements ImportBeanDefinitionRegistrar {

	private final Log logger = LogFactory.getLog(getClass());

	/**
	 * Register, escalate, and configure the standard auto proxy creator (APC) against the
	 * given registry. Works by finding the nearest annotation declared on the importing
	 * {@code @Configuration} class that has both {@code mode} and {@code proxyTargetClass}
	 * attributes. If {@code mode} is set to {@code PROXY}, the APC is registered; if
	 * {@code proxyTargetClass} is set to {@code true}, then the APC is forced to use
	 * subclass (CGLIB) proxying.
	 * <p>Several {@code @Enable*} annotations expose both {@code mode} and
	 * {@code proxyTargetClass} attributes. It is important to note that most of these
	 * capabilities end up sharing a {@linkplain AopConfigUtils#AUTO_PROXY_CREATOR_BEAN_NAME
	 * single APC}. For this reason, this implementation doesn't "care" exactly which
	 * annotation it finds -- as long as it exposes the right {@code mode} and
	 * {@code proxyTargetClass} attributes, the APC can be registered and configured all
	 * the same.
	 *
	 * 尝试为自动代理注册一个InfrastructureAdvisorAutoProxyCreator类型的自动代理创建者
	 * 并且会解析proxyTargetClass属性，如果有某个注解的proxyTargetClass属性设置为true
	 * 那么自动代理创建者的proxyTargetClass属性将会被设置为true，表示强制使用CGLIB代理。
	 *
	 * @param importingClassMetadata 引入该类的类的元数据。目前，只有@EnableCaching和@EnableTransactionManagement注解有可能引入该类
	 *                               因此将会传入，具有@EnableCaching和@EnableTransactionManagement注解的类的元数据
	 * @param registry               bean定义注册表
	 */
	@Override
	public void registerBeanDefinitions(AnnotationMetadata importingClassMetadata, BeanDefinitionRegistry registry) {
		boolean candidateFound = false;
		//获取类上的所有注解类型
		Set<String> annTypes = importingClassMetadata.getAnnotationTypes();
		//循环处理，对于具有mode和proxyTargetClass属性的注解统一处理
		for (String annType : annTypes) {
			//每一个注解的属性集合
			AnnotationAttributes candidate = AnnotationConfigUtils.attributesFor(importingClassMetadata, annType);
			if (candidate == null) {
				continue;
			}
			//获取mode属性
			Object mode = candidate.get("mode");
			//获取proxyTargetClass属性
			Object proxyTargetClass = candidate.get("proxyTargetClass");
			/*
			 * 如果存在这两个属性，并且类型也是匹配的
			 * 很多注解都有这两个属性，比如@EnableTransactionManagement、@EnableAsync、@EnableCaching、@EnableAspectJAutoProxy
			 */
			if (mode != null && proxyTargetClass != null && AdviceMode.class == mode.getClass() &&
					Boolean.class == proxyTargetClass.getClass()) {
				//
				candidateFound = true;
				//如果mode值是AdviceMode.PROXY值
				if (mode == AdviceMode.PROXY) {
					/*
					 * 调用AopConfigUtils.registerAutoProxyCreatorIfNecessary方法
					 * 尝试注册或者升级一个名为"org.springframework.aop.config.internalAutoProxyCreator"
					 * 类型为InfrastructureAdvisorAutoProxyCreator的自动代理创建者的bean定义
					 */
					AopConfigUtils.registerAutoProxyCreatorIfNecessary(registry);
					/*
					 * 如果有某个注解的proxyTargetClass属性设置为true
					 * 那么自动代理创建者的proxyTargetClass属性将会被设置为true，表示强制使用CGLIB代理。
					 */
					if ((Boolean) proxyTargetClass) {
						AopConfigUtils.forceAutoProxyCreatorToUseClassProxying(registry);
						return;
					}
				}
			}
		}
		//找不到EnableCaching注解
		if (!candidateFound && logger.isInfoEnabled()) {
			String name = getClass().getSimpleName();
			logger.info(String.format("%s was imported but no annotations were found " +
					"having both 'mode' and 'proxyTargetClass' attributes of type " +
					"AdviceMode and boolean respectively. This means that auto proxy " +
					"creator registration and configuration may not have occurred as " +
					"intended, and components may not be proxied as expected. Check to " +
					"ensure that %s has been @Import'ed on the same class where these " +
					"annotations are declared; otherwise remove the import of %s " +
					"altogether.", name, name, name));
		}
	}

}
