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

package org.springframework.scheduling.annotation;

import java.lang.annotation.Annotation;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.scheduling.config.TaskManagementConfigUtils;
import org.springframework.util.Assert;

/**
 * {@code @Configuration} class that registers the Spring infrastructure beans necessary
 * to enable proxy-based asynchronous method execution.
 * 配置类，继承了AbstractAsyncConfiguration
 *
 * @author Chris Beams
 * @author Stephane Nicoll
 * @author Juergen Hoeller
 * @since 3.1
 * @see EnableAsync
 * @see AsyncConfigurationSelector
 */
@Configuration(proxyBeanMethods = false)
@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
public class ProxyAsyncConfiguration extends AbstractAsyncConfiguration {

	/**
	 * 一个@Bean方法，beanName为"org.springframework.context.annotation.internalAsyncAnnotationProcessor"
	 */
	@Bean(name = TaskManagementConfigUtils.ASYNC_ANNOTATION_PROCESSOR_BEAN_NAME)
	@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
	public AsyncAnnotationBeanPostProcessor asyncAdvisor() {
		//判断是否具有@EnableAsync注解
		Assert.notNull(this.enableAsync, "@EnableAsync annotation metadata was not injected");
		//新建一个AsyncAnnotationBeanPostProcessor实例
		AsyncAnnotationBeanPostProcessor bpp = new AsyncAnnotationBeanPostProcessor();
		//设置从AsyncConfigure中获取的配置信息，包括执行器和异常处理器
		bpp.configure(this.executor, this.exceptionHandler);
		//获取annotation属性值，这表示自定义的异步任务注解
		Class<? extends Annotation> customAsyncAnnotation = this.enableAsync.getClass("annotation");
		//如果值不等于属性默认值，这说明使用者手动设置了值
		if (customAsyncAnnotation != AnnotationUtils.getDefaultValue(EnableAsync.class, "annotation")) {
			//那么将设置的注解作为异步任务注解，并且不会解析默认注解
			bpp.setAsyncAnnotationType(customAsyncAnnotation);
		}
		//设置proxyTargetClass属性，它决定了采用什么样的代理
		bpp.setProxyTargetClass(this.enableAsync.getBoolean("proxyTargetClass"));
		//设置order属性，它决定了后处理执行顺序
		bpp.setOrder(this.enableAsync.<Integer>getNumber("order"));
		return bpp;
	}

}
