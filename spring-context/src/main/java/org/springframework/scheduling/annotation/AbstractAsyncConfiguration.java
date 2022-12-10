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

import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.Supplier;

import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportAware;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.lang.Nullable;
import org.springframework.util.CollectionUtils;
import org.springframework.util.function.SingletonSupplier;

/**
 * Abstract base {@code Configuration} class providing common structure for enabling
 * Spring's asynchronous method execution capability.
 * 抽象基配置类，为启用 Spring 的异步方法执行功能提供了通用实现。
 * 该类实现了ImportAware感知接口，可以获取当前注解的自身元数据
 *
 * @author Chris Beams
 * @author Juergen Hoeller
 * @author Stephane Nicoll
 * @since 3.1
 * @see EnableAsync
 */
@Configuration(proxyBeanMethods = false)
public abstract class AbstractAsyncConfiguration implements ImportAware {

	@Nullable
	protected AnnotationAttributes enableAsync;

	@Nullable
	protected Supplier<Executor> executor;

	@Nullable
	protected Supplier<AsyncUncaughtExceptionHandler> exceptionHandler;


	/**
	 * 该方法被ImportAwareBeanPostProcessor后处理器调用，用于将当前注解自身的元数据设置进来
	 */
	@Override
	public void setImportMetadata(AnnotationMetadata importMetadata) {
		//获取注解属性设置给enableAsync
		this.enableAsync = AnnotationAttributes.fromMap(
				importMetadata.getAnnotationAttributes(EnableAsync.class.getName()));
		if (this.enableAsync == null) {
			throw new IllegalArgumentException(
					"@EnableAsync is not present on importing class " + importMetadata.getClassName());
		}
	}

	/**
	 * Collect any {@link AsyncConfigurer} beans through autowiring.
	 * 如果beanFactory中还有其他AsyncConfigurer类型的bean（当然可以没有），那么全部注入进来
	 * AsyncConfigurer是一个自定义的异步任务配置类，我们可以通过该配置类来配置自定义的任务执行器
	 * 在Spring异步任务学习的时候我们就说过该扩展点
	 */
	@Autowired
	void setConfigurers(ObjectProvider<AsyncConfigurer> configurers) {
		Supplier<AsyncConfigurer> configurer = SingletonSupplier.of(() -> {
			List<AsyncConfigurer> candidates = configurers.stream().toList();
			//如果没有，那么直接返回
			if (CollectionUtils.isEmpty(candidates)) {
				return null;
			}
			//如果配置类多个配置类，那么抛出异常
			if (candidates.size() > 1) {
				throw new IllegalStateException("Only one AsyncConfigurer may exist");
			}
			return candidates.get(0);
		});
		//获取配置类中配置的异步执行器设置给executor属性
		this.executor = adapt(configurer, AsyncConfigurer::getAsyncExecutor);
		//获取配置类中配置的异常处理器设置给exceptionHandler属性
		this.exceptionHandler = adapt(configurer, AsyncConfigurer::getAsyncUncaughtExceptionHandler);
	}

	private <T> Supplier<T> adapt(Supplier<AsyncConfigurer> supplier, Function<AsyncConfigurer, T> provider) {
		return () -> {
			AsyncConfigurer configurer = supplier.get();
			return (configurer != null ? provider.apply(configurer) : null);
		};
	}

}
