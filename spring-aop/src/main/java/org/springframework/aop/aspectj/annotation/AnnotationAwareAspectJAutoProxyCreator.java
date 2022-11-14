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

package org.springframework.aop.aspectj.annotation;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.aop.Advisor;
import org.springframework.aop.aspectj.autoproxy.AspectJAwareAdvisorAutoProxyCreator;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;

/**
 * {@link AspectJAwareAdvisorAutoProxyCreator} subclass that processes all AspectJ
 * annotation aspects in the current application context, as well as Spring Advisors.
 *
 * <p>Any AspectJ annotated classes will automatically be recognized, and their
 * advice applied if Spring AOP's proxy-based model is capable of applying it.
 * This covers method execution joinpoints.
 *
 * <p>If the &lt;aop:include&gt; element is used, only @AspectJ beans with names matched by
 * an include pattern will be considered as defining aspects to use for Spring auto-proxying.
 *
 * <p>Processing of Spring Advisors follows the rules established in
 * {@link org.springframework.aop.framework.autoproxy.AbstractAdvisorAutoProxyCreator}.
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @see org.springframework.aop.aspectj.annotation.AspectJAdvisorFactory
 * @since 2.0
 */
@SuppressWarnings("serial")
public class AnnotationAwareAspectJAutoProxyCreator extends AspectJAwareAdvisorAutoProxyCreator {

	/**
	 * <p>
	 * 就是此前<aop:include/>标签解析后的模式集合，默认为null
	 * <p>
	 * <aop:include/>标签配置的name属性，将会转换为Pattern对象，因此它应该是一个正则表达式
	 * Spring会自动进行类型转换，通过Pattern.compile将字符串转换为一个Pattern模式对象
	 */
	@Nullable
	private List<Pattern> includePatterns;

	@Nullable
	private AspectJAdvisorFactory aspectJAdvisorFactory;

	/**
	 * AspectAdvisor构建者，在BeanFactoryAware接口回调的setBeanFactory方法时被初始化
	 * 类型为BeanFactoryAspectJAdvisorsBuilderAdapter
	 */
	@Nullable
	private BeanFactoryAspectJAdvisorsBuilder aspectJAdvisorsBuilder;


	/**
	 * Set a list of regex patterns, matching eligible @AspectJ bean names.
	 * <p>Default is to consider all @AspectJ beans as eligible.
	 * 设置正则表达式模式的列表，匹配符合条件的@AspectJ  bean的名称
	 * 默认值是将所有@AspectJ视为符合条件的。
	 *
	 * @param patterns 参数字符串集合
	 */
	public void setIncludePatterns(List<String> patterns) {
		//配置为一个空集合
		this.includePatterns = new ArrayList<>(patterns.size());
		//遍历全部patternText字符串集合
		for (String patternText : patterns) {
			//通过Pattern.compile，转换为正则表达式模式对象，加入到includePatterns列表中
			this.includePatterns.add(Pattern.compile(patternText));
		}
	}

	public void setAspectJAdvisorFactory(AspectJAdvisorFactory aspectJAdvisorFactory) {
		Assert.notNull(aspectJAdvisorFactory, "AspectJAdvisorFactory must not be null");
		this.aspectJAdvisorFactory = aspectJAdvisorFactory;
	}

	/**
	 * 子类AnnotationAwareAspectJAutoProxyCreator重写的方法
	 *
	 * @param beanFactory bean工厂
	 */
	@Override
	protected void initBeanFactory(ConfigurableListableBeanFactory beanFactory) {
		//调用父类的方法，这会初始化父类的advisorRetrievalHelper
		super.initBeanFactory(beanFactory);
		//创建aspectJAdvisor工厂，实际类型为ReflectiveAspectJAdvisorFactory
		if (this.aspectJAdvisorFactory == null) {

			this.aspectJAdvisorFactory = new ReflectiveAspectJAdvisorFactory(beanFactory);
		}
		//创建aspectJAdvisor构建者，实际类型为BeanFactoryAspectJAdvisorsBuilderAdapter
		//它是AnnotationAwareAspectJAutoProxyCreator的内部类
		this.aspectJAdvisorsBuilder =
				new BeanFactoryAspectJAdvisorsBuilderAdapter(beanFactory, this.aspectJAdvisorFactory);
	}


	/**
	 * 查找候选Advisors
	 *
	 * @return
	 */
	@Override
	protected List<Advisor> findCandidateAdvisors() {
		// Add all the Spring advisors found according to superclass rules.
		/*
		 * 1 调用父类的findCandidateAdvisors方法查找基于XML配置的已解析的Advisors
		 */
		List<Advisor> advisors = super.findCandidateAdvisors();
		// Build Advisors for all AspectJ aspects in the bean factory.
		/*
		 * 2 从beanFactory中查找所有具有@Aspect注解的切面bean定义并构建
		 */
		if (this.aspectJAdvisorsBuilder != null) {
			advisors.addAll(this.aspectJAdvisorsBuilder.buildAspectJAdvisors());
		}
		//将从XML配置和注解配置中找到的Advisor合并到一个集合中返回
		return advisors;
	}

	@Override
	protected boolean isInfrastructureClass(Class<?> beanClass) {
		// Previously we setProxyTargetClass(true) in the constructor, but that has too
		// broad an impact. Instead we now override isInfrastructureClass to avoid proxying
		// aspects. I'm not entirely happy with that as there is no good reason not
		// to advise aspects, except that it causes advice invocation to go through a
		// proxy, and if the aspect implements e.g the Ordered interface it will be
		// proxied by that interface and fail at runtime as the advice method is not
		// defined on the interface. We could potentially relax the restriction about
		// not advising aspects in the future.
		return (super.isInfrastructureClass(beanClass) ||
				(this.aspectJAdvisorFactory != null && this.aspectJAdvisorFactory.isAspect(beanClass)));
	}

	/**
	 * Check whether the given aspect bean is eligible for auto-proxying.
	 * <p>检查给定切面 bean 是否符合自动代理条件。
	 * <p>If no &lt;aop:include&gt; elements were used then "includePatterns" will be
	 * {@code null} and all beans are included. If "includePatterns" is non-null,
	 * then one of the patterns must match.
	 * <p>如果没有配置<aop:include/>标签，则"包含模式"将为null，并且包括所有 bean。
	 * 如果"包含模式"为非null，则其中一个模式必须匹配。
	 * </p>
	 */
	protected boolean isEligibleAspectBean(String beanName) {
		//如果没有设置包含模式，即没有<aop:include/>标签，那么默认返回true，表示全部符合条件
		if (this.includePatterns == null) {
			return true;
		}
		//如果设置了包含模式，那么给定的beanName至少匹配一个模式
		else {
			for (Pattern pattern : this.includePatterns) {
				//如果给定的beanName至少匹配一个模式（正则表达式），就直接返回true
				if (pattern.matcher(beanName).matches()) {
					return true;
				}
			}
			//都不匹配，那么返回false
			return false;
		}
	}


	/**
	 * Subclass of BeanFactoryAspectJAdvisorsBuilderAdapter that delegates to
	 * surrounding AnnotationAwareAspectJAutoProxyCreator facilities.
	 */
	private class BeanFactoryAspectJAdvisorsBuilderAdapter extends BeanFactoryAspectJAdvisorsBuilder {

		public BeanFactoryAspectJAdvisorsBuilderAdapter(
				ListableBeanFactory beanFactory, AspectJAdvisorFactory advisorFactory) {

			super(beanFactory, advisorFactory);
		}

		@Override
		protected boolean isEligibleBean(String beanName) {
			//调用AnnotationAwareAspectJAutoProxyCreator对象的isEligibleAspectBean方法
			return AnnotationAwareAspectJAutoProxyCreator.this.isEligibleAspectBean(beanName);
		}
	}

}
