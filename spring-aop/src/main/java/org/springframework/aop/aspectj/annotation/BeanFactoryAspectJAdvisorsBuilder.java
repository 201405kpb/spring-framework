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

package org.springframework.aop.aspectj.annotation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.aspectj.lang.reflect.PerClauseKind;

import org.springframework.aop.Advisor;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;

/**
 * Helper for retrieving @AspectJ beans from a BeanFactory and building
 * Spring Advisors based on them, for use with auto-proxying.
 *
 * @author Juergen Hoeller
 * @since 2.0.2
 * @see AnnotationAwareAspectJAutoProxyCreator
 */
public class BeanFactoryAspectJAdvisorsBuilder {

	/**
	 * 当前 IoC 容器，DefaultListableBeanFactory
	 */
	private final ListableBeanFactory beanFactory;
	/**
	 * Advisor 工厂，用于解析 @AspectJ 注解的 Bean 中的 Advisor
	 */
	private final AspectJAdvisorFactory advisorFactory;
	/**
	 * 用于缓存带有 @AspectJ 注解的 Bean 的名称
	 */
	@Nullable
	private volatile List<String> aspectBeanNames;
	/**
	 * 缓存 @AspectJ 注解的单例 Bean 中解析出来的 Advisor
	 * key：带有 @AspectJ 注解的 beanName
	 * value：其内部解析出来的 Advisor 集合
	 */
	private final Map<String, List<Advisor>> advisorsCache = new ConcurrentHashMap<>();
	/**
	 * 缓存 @AspectJ 注解的非单例 Bean 的元数据实例构建工厂
	 * key：带有 @AspectJ 注解的 beanName（非单例）
	 * value：对应的元数据工厂对象
	 */
	private final Map<String, MetadataAwareAspectInstanceFactory> aspectFactoryCache = new ConcurrentHashMap<>();

	/**
	 * Create a new BeanFactoryAspectJAdvisorsBuilder for the given BeanFactory.
	 * @param beanFactory the ListableBeanFactory to scan
	 */
	public BeanFactoryAspectJAdvisorsBuilder(ListableBeanFactory beanFactory) {
		this(beanFactory, new ReflectiveAspectJAdvisorFactory(beanFactory));
	}

	/**
	 * Create a new BeanFactoryAspectJAdvisorsBuilder for the given BeanFactory.
	 * @param beanFactory the ListableBeanFactory to scan
	 * @param advisorFactory the AspectJAdvisorFactory to build each Advisor with
	 */
	public BeanFactoryAspectJAdvisorsBuilder(ListableBeanFactory beanFactory, AspectJAdvisorFactory advisorFactory) {
		Assert.notNull(beanFactory, "ListableBeanFactory must not be null");
		Assert.notNull(advisorFactory, "AspectJAdvisorFactory must not be null");
		this.beanFactory = beanFactory;
		this.advisorFactory = advisorFactory;
	}


	/**
	 * Look for AspectJ-annotated aspect beans in the current bean factory,
	 * and return to a list of Spring AOP Advisors representing them.
	 * <p>Creates a Spring Advisor for each AspectJ advice method.
	 * 在当bean工厂中查找具有@AspectJ注解的切面bean，并基于它们的配置构建对应的的Advisor
	 * 将切面类中全部合法的通知方法和引介字段转换为Advisor通知器集合
	 * 最后返回所有构建的Advisor集合
	 * @return the list of {@link org.springframework.aop.Advisor} beans
	 * @see #isEligibleBean
	 */
	public List<Advisor> buildAspectJAdvisors() {
		//获取缓存的切面名称集合
		List<String> aspectNames = this.aspectBeanNames;
		//如果集合为null，表示第一次调用该方法
		if (aspectNames == null) {
			synchronized (this) {
				aspectNames = this.aspectBeanNames;
				//如果集合为null
				if (aspectNames == null) {
					//该集合用于保存构建的Advisor
					List<Advisor> advisors = new ArrayList<>();
					//初始化aspectNames集合
					aspectNames = new ArrayList<>();
					/*
					 * 获取全部Object类型的bean定义的beanName数组，注意是Object类型
					 */
					String[] beanNames = BeanFactoryUtils.beanNamesForTypeIncludingAncestors(
							this.beanFactory, Object.class, true, false);
					/*遍历beanName数组，查找所有切面类中的头通知器*/
					for (String beanName : beanNames) {
						/*
						 * 判断切面bean的名称是否合格，也就是符合子标签<aop:include/>的配置模式
						 *
						 * 该方法在BeanFactoryAspectJAdvisorsBuilder中默认返回true，
						 * 被子类BeanFactoryAspectJAdvisorsBuilderAdapter重写，重写的方法中会
						 * 调用AnnotationAwareAspectJAutoProxyCreator的isEligibleAspectBean继续判断
						 */
						if (!isEligibleBean(beanName)) {
							//如果不合格，直接跳过，判断下一个beanName
							continue;
						}
						// We must be careful not to instantiate beans eagerly as in this case they
						// would be cached by the Spring container but would not have been weaved.
						//获取当前beanName对应的bean定义的所属类的类型
						Class<?> beanType = this.beanFactory.getType(beanName, false);
						//如果类型为null，直接跳过，判断下一个beanName
						if (beanType == null) {
							continue;
						}
						/*
						 * 判断是否是切面类，即判断当前类以及它继承的超类或者实现的接口上是否具有@Aspect注解
						 * 只有具有@Aspect注解的类才能被进一步处理，也就是解析为Advisor
						 *
						 * 从这里能够看出来，bean定义只有先被注册到容器中，才能进一步解析@Aspect注解
						 * 因此对于切面类我们通常需要同时添加@Component和@Aspect注解
						 */
						if (this.advisorFactory.isAspect(beanType)) {
							//当前beanName加入到aspectNames缓存中，后续从缓存中直接获取
							aspectNames.add(beanName);
							//根据当前beanType和beanName，新建一个AspectMetadata切面元数据
							//保存了@Aspect注解的信息以及当前切面类的信息
							AspectMetadata amd = new AspectMetadata(beanType, beanName);
							/*
							 * 如果没有设置@Aspect注解的value属性值，那么就是默认就是单例的切面类
							 * value可以设置为值有perthis()、pertarget()、percflow()等等，可以设置切面类的作用域
							 */
							if (amd.getAjType().getPerClause().getKind() == PerClauseKind.SINGLETON) {
								/*新建一个BeanFactoryAspectInstanceFactory工厂对象，用于创建AspectJ 切面实例*/
								MetadataAwareAspectInstanceFactory factory =
										new BeanFactoryAspectInstanceFactory(this.beanFactory, beanName);
								/*
								 * 通过advisorFactory调用getAdvisors方法
								 * 将当前切面类中全部合法的通知方法和引介字段转换为Advisor通知器集合
								 */
								List<Advisor> classAdvisors = this.advisorFactory.getAdvisors(factory);
								//如果当前切面类是单例bean
								if (this.beanFactory.isSingleton(beanName)) {
									//将当前切面类beanName和内部的通知器集合存入advisorsCache缓存
									this.advisorsCache.put(beanName, classAdvisors);
								}
								else {
									//否则，将当前切面类beanName和切面实例工厂存入aspectFactoryCache缓存
									this.aspectFactoryCache.put(beanName, factory);
								}
								//当前切面类的所有通知器加入到advisors总集合中
								advisors.addAll(classAdvisors);
							}
							//对于其他切面类的作用域的处理
							else {
								// Per target or per this.
								//如果当前切面bean是单例的，那么抛出异常
								if (this.beanFactory.isSingleton(beanName)) {
									throw new IllegalArgumentException("Bean with name '" + beanName +
											"' is a singleton, but aspect instantiation model is not singleton");
								}
								/*新建一个PrototypeAspectInstanceFactory工厂对象，用于创建AspectJ 切面实例，可能会多次实例化*/
								MetadataAwareAspectInstanceFactory factory =
										new PrototypeAspectInstanceFactory(this.beanFactory, beanName);
								//将当前切面类beanName和切面实例工厂存入aspectFactoryCache缓存
								this.aspectFactoryCache.put(beanName, factory);
								//当前切面类的所有通知器加入到advisors总集合中
								advisors.addAll(this.advisorFactory.getAdvisors(factory));
							}
						}
					}
					//aspectNames赋给aspectBeanNames缓存起来，后续从缓存中直接获取
					this.aspectBeanNames = aspectNames;
					//返回通知器集合
					return advisors;
				}
			}
		}
		//如果切面名集合为空，那么返回一个空list
		if (aspectNames.isEmpty()) {
			return Collections.emptyList();
		}
		List<Advisor> advisors = new ArrayList<>();
		/*
		 * 遍历全部切面名集合，
		 */
		for (String aspectName : aspectNames) {
			//从advisorsCache缓存中根据切面名获取该切面的通知器集合
			List<Advisor> cachedAdvisors = this.advisorsCache.get(aspectName);
			//如果不为null，说明已缓存，那么直接获取
			if (cachedAdvisors != null) {
				advisors.addAll(cachedAdvisors);
			} else {
				//如果为null，说明这个切面bean不是单例bean，那么从新获取
				MetadataAwareAspectInstanceFactory factory = this.aspectFactoryCache.get(aspectName);
				advisors.addAll(this.advisorFactory.getAdvisors(factory));
			}
		}
		//返回通知器集合
		return advisors;
	}

	/**
	 * Return whether the aspect bean with the given name is eligible.
	 * 确定具有给定名称的切面 bean 是否合格，默认实现返回true
	 * @param beanName the name of the aspect bean
	 * @return whether the bean is eligible
	 */
	protected boolean isEligibleBean(String beanName) {
		return true;
	}

}
