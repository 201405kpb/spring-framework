/*
 * Copyright 2002-2022 the original author or authors.
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

package org.springframework.aop.framework.autoproxy;

import org.springframework.aop.framework.AbstractAdvisingBeanPostProcessor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.lang.Nullable;

/**
 * Extension of {@link AbstractAutoProxyCreator} which implements {@link BeanFactoryAware},
 * adds exposure of the original target class for each proxied bean
 * ({@link AutoProxyUtils#ORIGINAL_TARGET_CLASS_ATTRIBUTE}),
 * and participates in an externally enforced target-class mode for any given bean
 * ({@link AutoProxyUtils#PRESERVE_TARGET_CLASS_ATTRIBUTE}).
 * This post-processor is therefore aligned with {@link AbstractAutoProxyCreator}.
 *
 * @author Juergen Hoeller
 * @since 4.2.3
 * @see AutoProxyUtils#shouldProxyTargetClass
 * @see AutoProxyUtils#determineTargetClass
 */
@SuppressWarnings("serial")
public abstract class AbstractBeanFactoryAwareAdvisingPostProcessor extends AbstractAdvisingBeanPostProcessor
		implements BeanFactoryAware {

	@Nullable
	private ConfigurableListableBeanFactory beanFactory;


	@Override
	public void setBeanFactory(BeanFactory beanFactory) {
		this.beanFactory = (beanFactory instanceof ConfigurableListableBeanFactory clbf ? clbf : null);
	}

	/**
	 * 用于处理@Configuration注解标注的代配置类使其强制采用CGLIB代理
	 */
	@Override
	protected ProxyFactory prepareProxyFactory(Object bean, String beanName) {
		if (this.beanFactory != null) {
			//公开指定 bean 的给定目标类，主要就是设置bean定义的ORIGINAL_TARGET_CLASS_ATTRIBUTE属性，
			//即"org.springframework.aop.framework.autoproxy.AutoProxyUtils.originalTargetClass"属性，value为beanClass
			//也就是保存其原来的类型
			AutoProxyUtils.exposeTargetClass(this.beanFactory, beanName, bean.getClass());
		}
		//调用父类的方法创建ProxyFactory
		ProxyFactory proxyFactory = super.prepareProxyFactory(bean, beanName);
		/*
		 * 这里的逻辑和"AbstractAutoProxyCreator"差不多，处理@Configuration配置类
		 *
		 * 判断：如果proxyTargetClass属性为false，并且存在beanFactory，并且当前bean定义存在PRESERVE_TARGET_CLASS_ATTRIBUTE属性，
		 * 即"org.springframework.aop.framework.autoproxy.AutoProxyUtils.preserveTargetClass"属性，并且值为true
		 *
		 * 我们在前面讲解"ConfigurationClassPostProcessor配置类后处理器"的文章中就见过该属性
		 * 对于@Configuration注解标注的代理类，它的bean定义会添加这个属性并且值为true，表示强制走CGLIB代理
		 */
		if (!proxyFactory.isProxyTargetClass() && this.beanFactory != null &&
				AutoProxyUtils.shouldProxyTargetClass(this.beanFactory, beanName)) {
			//满足三个条件，即使配置是基于JDK的代理，对于当前类，仍然采用CGLIB的代理
			proxyFactory.setProxyTargetClass(true);
		}
		return proxyFactory;
	}

	@Override
	protected boolean isEligible(Object bean, String beanName) {
		return (!AutoProxyUtils.isOriginalInstance(beanName, bean.getClass()) &&
				super.isEligible(bean, beanName));
	}

}
