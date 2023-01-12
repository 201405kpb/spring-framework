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

import org.springframework.context.annotation.AdviceMode;
import org.springframework.context.annotation.AdviceModeImportSelector;
import org.springframework.lang.NonNull;

/**
 * Selects which implementation of {@link AbstractAsyncConfiguration} should
 * be used based on the value of {@link EnableAsync#mode} on the importing
 * {@code @Configuration} class.
 * 实现了AdviceModeImportSelector，泛型类型为@EnableAsync注解
 *
 *
 * @author Chris Beams
 * @author Juergen Hoeller
 * @since 3.1
 * @see EnableAsync
 * @see ProxyAsyncConfiguration
 */
public class AsyncConfigurationSelector extends AdviceModeImportSelector<EnableAsync> {

	/**
	 * 支持Aspectj的静态代理织入
	 */
	private static final String ASYNC_EXECUTION_ASPECT_CONFIGURATION_CLASS_NAME =
			"org.springframework.scheduling.aspectj.AspectJAsyncConfiguration";


	/**
	 * Returns {@link ProxyAsyncConfiguration} or {@code AspectJAsyncConfiguration}
	 * for {@code PROXY} and {@code ASPECTJ} values of {@link EnableAsync#mode()},
	 * respectively.
	 *
	 * 分别为 EnableAsync.mode的 PROXY和ASPECTJ值返回ProxyAsyncConfiguration或AspectJAsyncConfiguration。
	 */
	@Override
	@NonNull
	public String[] selectImports(AdviceMode adviceMode) {
		//判断注解的adviceMode属性值
		return switch (adviceMode) {
			//一般都是PROXY，因此将会注册ProxyAsyncConfiguration的bean定义，进行动态代理
			case PROXY -> new String[] {ProxyAsyncConfiguration.class.getName()};
			//支持Aspectj的静态代理织入
			case ASPECTJ -> new String[] {ASYNC_EXECUTION_ASPECT_CONFIGURATION_CLASS_NAME};
		};
	}

}
