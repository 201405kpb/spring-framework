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

import org.springframework.beans.factory.parsing.Problem;
import org.springframework.beans.factory.parsing.ProblemReporter;
import org.springframework.core.type.MethodMetadata;
import org.springframework.lang.Nullable;

/**
 * Represents a {@link Configuration @Configuration} class method annotated with
 * {@link Bean @Bean}.
 *
 * @author Chris Beams
 * @author Juergen Hoeller
 * @author Sam Brannen
 * @since 3.0
 * @see ConfigurationClass
 * @see ConfigurationClassParser
 * @see ConfigurationClassBeanDefinitionReader
 */
final class BeanMethod extends ConfigurationMethod {

	BeanMethod(MethodMetadata metadata, ConfigurationClass configurationClass) {
		super(metadata, configurationClass);
	}


	/**
	 * 校验@Bean注解标注的方法
	 */
	@Override
	public void validate(ProblemReporter problemReporter) {
		//如果是静态方法，不需要校验，直接返回
		if (getMetadata().isStatic()) {
			// static @Bean methods have no constraints to validate -> return immediately
			return;
		}
		//如果当前方法所属配置类具有@Configuration注解
		if (this.configurationClass.getMetadata().isAnnotated(Configuration.class.getName())) {
			//如果当前方法不能被重写，那么抛出异常："@Bean method '%s' must not be private or final; change the method's modifiers to continue"
			//因为只有可重写的方法才能被CGLiB代理
			//如果是final、static、private修饰的方法，那么isOverridable方法就返回true
			if (!getMetadata().isOverridable()) {
				// instance @Bean methods within @Configuration classes must be overridable to accommodate CGLIB
				problemReporter.error(new NonOverridableMethodError());
			}
		}
	}

	@Override
	public boolean equals(@Nullable Object obj) {
		return (this == obj ||
				(obj instanceof BeanMethod that && this.metadata.equals(that.metadata)));
	}

	@Override
	public int hashCode() {
		return this.metadata.hashCode();
	}

	@Override
	public String toString() {
		return "BeanMethod: " + this.metadata;
	}

	private class NonOverridableMethodError extends Problem {

		NonOverridableMethodError() {
			super("@Bean method '%s' must not be private or final; change the method's modifiers to continue."
					.formatted(getMetadata().getMethodName()), getResourceLocation());
		}
	}

}
