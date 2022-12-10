/*
 * Copyright 2002-2018 the original author or authors.
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

import org.springframework.beans.factory.BeanNameAware;
import org.springframework.lang.Nullable;

/**
 * {@code BeanPostProcessor} implementation that creates AOP proxies based on all
 * candidate {@code Advisor}s in the current {@code BeanFactory}. This class is
 * completely generic; it contains no special code to handle any particular aspects,
 * such as pooling aspects.
 *
 * <p>It's possible to filter out advisors - for example, to use multiple post processors
 * of this type in the same factory - by setting the {@code usePrefix} property to true,
 * in which case only advisors beginning with the DefaultAdvisorAutoProxyCreator's bean
 * name followed by a dot (like "aapc.") will be used. This default prefix can be changed
 * from the bean name by setting the {@code advisorBeanNamePrefix} property.
 * The separator (.) will also be used in this case.
 *
 * @author Rod Johnson
 * @author Rob Harrop
 */
@SuppressWarnings("serial")
public class DefaultAdvisorAutoProxyCreator extends AbstractAdvisorAutoProxyCreator implements BeanNameAware {

	/** Separator between prefix and remainder of bean name. */
	public static final String SEPARATOR = ".";


	private boolean usePrefix = false;

	@Nullable
	private String advisorBeanNamePrefix;


	/**
	 * Set whether to only include advisors with a certain prefix in the bean name.
	 * <p>Default is {@code false}, including all beans of type {@code Advisor}.
	 * @see #setAdvisorBeanNamePrefix
	 */
	public void setUsePrefix(boolean usePrefix) {
		this.usePrefix = usePrefix;
	}

	/**
	 * Return whether to only include advisors with a certain prefix in the bean name.
	 */
	public boolean isUsePrefix() {
		return this.usePrefix;
	}

	/**
	 * Set the prefix for bean names that will cause them to be included for
	 * auto-proxying by this object. This prefix should be set to avoid circular
	 * references. Default value is the bean name of this object + a dot.
	 * 设置Advisor通知器 beanName 的前缀
	 * @param advisorBeanNamePrefix the exclusion prefix
	 */
	public void setAdvisorBeanNamePrefix(@Nullable String advisorBeanNamePrefix) {
		this.advisorBeanNamePrefix = advisorBeanNamePrefix;
	}

	/**
	 * Return the prefix for bean names that will cause them to be included
	 * for auto-proxying by this object.
	 * 获取Advisor通知器 beanName 的前缀
	 */
	@Nullable
	public String getAdvisorBeanNamePrefix() {
		return this.advisorBeanNamePrefix;
	}

	/**
	 *
	 * @param name the name of the bean in the factory.
	 * bean实例创建完毕之后自动回调的方法，将当前创建的bean的beanName传递进来
	 */
	@Override
	public void setBeanName(String name) {
		// If no infrastructure bean name prefix has been set, override it.
		//如果未设置Advisor通知器 beanName 的前缀，那么使用beanName+"."作为默认前缀
		if (this.advisorBeanNamePrefix == null) {
			this.advisorBeanNamePrefix = name + SEPARATOR;
		}
	}

	/**
	 *  Consider {@code Advisor} beans with the specified prefix as eligible, if activated.
	 * 判断是否是合格的候选AdvisorBean
	 * 如果启用前缀匹配，那么beanName匹配前缀，否则默认返回true
	 * @param beanName 某个Advisor的beanName
	 */
	@Override
	protected boolean isEligibleAdvisorBean(String beanName) {
		//如果没有启用前缀匹配，那么直接返回true，表示合格
		if (!isUsePrefix()) {
			return true;
		}
		//那么如果prefix不为null并且当前Advisor的beanName以指定的前缀开始，那么说明合格，否则返回false
		String prefix = getAdvisorBeanNamePrefix();
		return (prefix != null && beanName.startsWith(prefix));
	}

}
