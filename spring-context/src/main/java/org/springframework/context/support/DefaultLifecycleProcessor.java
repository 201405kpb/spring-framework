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

package org.springframework.context.support;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.*;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Default implementation of the {@link LifecycleProcessor} strategy.
 * <p> LifecycleProcessor 策略的默认实现</p>
 * <p>主要自动启动/停止BeanFactory中的所有实现 Lifecycle 的单例Bean对象</p>
 *
 * @author Mark Fisher
 * @author Juergen Hoeller
 * @since 3.0
 */
public class DefaultLifecycleProcessor implements LifecycleProcessor, BeanFactoryAware {

	private final Log logger = LogFactory.getLog(getClass());

	/**
	 * 指定为任何阶段(具有相同'phase'值的 SmartLifecycle Bean组)的关闭分配的最大 时间(以毫秒为单位)
	 */
	private volatile long timeoutPerShutdownPhase = 30000;

	/**
	 * 是否正在允许的标记
	 */
	private volatile boolean running;

	/**
	 * 当前BeanFactory
	 */
	@Nullable
	private volatile ConfigurableListableBeanFactory beanFactory;

	/**
	 * Specify the maximum time allotted in milliseconds for the shutdown of
	 * any phase (group of SmartLifecycle beans with the same 'phase' value).
	 * <p>指定为任何阶段(具有相同'phase'值的 SmartLifecycle Bean组)的关闭分配的最大
	 * 时间(以毫秒为单位)</p>
	 * <p>The default value is 30 seconds.
	 * <p>默认值是30秒</p>
	 */
	public void setTimeoutPerShutdownPhase(long timeoutPerShutdownPhase) {
		this.timeoutPerShutdownPhase = timeoutPerShutdownPhase;
	}

	/**
	 * 获取当前 BeanFactory
	 *
	 * @return
	 */
	private ConfigurableListableBeanFactory getBeanFactory() {
		ConfigurableListableBeanFactory beanFactory = this.beanFactory;
		//如果 beanFactory 为 null，抛出异常
		Assert.state(beanFactory != null, "No BeanFactory available");
		return beanFactory;
	}

	@Override
	public void setBeanFactory(BeanFactory beanFactory) {
		//如果 beanFactory 不是 ConfigurableListableBeanFactory 的实例
		if (!(beanFactory instanceof ConfigurableListableBeanFactory clbf)) {
			// 抛出非法参数异常：DefaultLifecycleProcessor 需要一个 ConfigurableListableBeanFactory
			throw new IllegalArgumentException(
					"DefaultLifecycleProcessor requires a ConfigurableListableBeanFactory: " + beanFactory);
		}
		this.beanFactory = clbf;
	}


	// Lifecycle implementation

	/**
	 * Start all registered beans that implement {@link Lifecycle} and are <i>not</i>
	 * already running. Any bean that implements {@link SmartLifecycle} will be
	 * started within its 'phase', and all phases will be ordered from lowest to
	 * highest value. All beans that do not implement {@link SmartLifecycle} will be
	 * started in the default phase 0. A bean declared as a dependency of another bean
	 * will be started before the dependent bean regardless of the declared phase.
	 * <p>启动所有实现生命周期且尚未运行的已注册bean。任何实现SmartLifecycle的bean都将在它的“阶段”中启动，
	 * 所有阶段都将按从低到高的顺序排列。所有没有实现SmartLifecycle的bean将在默认的阶段0启动。
	 * 声明为另一个bean的依赖项的bean将在该依赖bean之前启动，而不管声明的阶段是什么。</p>
	 */
	@Override
	public void start() {
		startBeans(false);
		this.running = true;
	}

	/**
	 * Stop all registered beans that implement {@link Lifecycle} and <i>are</i>
	 * currently running. Any bean that implements {@link SmartLifecycle} will be
	 * stopped within its 'phase', and all phases will be ordered from highest to
	 * lowest value. All beans that do not implement {@link SmartLifecycle} will be
	 * stopped in the default phase 0. A bean declared as dependent on another bean
	 * will be stopped before the dependency bean regardless of the declared phase.
	 * <p>停止所有实现生命周期并正在运行的已注册bean。任何实现SmartLifecycle的bean都将
	 * 在其“阶段”内停止，所有阶段都将按从高到低的顺序排列.所有没有实现SmartLifecycle的
	 * bean将在默认的阶段0停止。声明为依赖于另一个bean的bean将在该依赖bean之前停止，
	 * 而与声明的阶段无关。</p>
	 */
	@Override
	public void stop() {
		stopBeans();
		this.running = false;
	}

	@Override
	public void onRefresh() {
		startBeans(true);
		this.running = true;
	}

	@Override
	public void onClose() {
		stopBeans();
		this.running = false;
	}

	@Override
	public boolean isRunning() {
		return this.running;
	}


	// Internal helpers

	/**
	 * @param autoStartupOnly 是否只包含自动启动的标记。autoStartupOnly等于true时，bean必须实现SmartLifecycle接口，并且isAutoStartup()返回true，才会被放入LifecycleGroup
	 */
	private void startBeans(boolean autoStartupOnly) {
		//检索所有适用的生命周期Bean：所有已经创建的单例Bean，以及所有 SmartLifeCycle Bean(即使它们被标记未延迟初始化)
		Map<String, Lifecycle> lifecycleBeans = getLifecycleBeans();
		//各 phases 所对应的 LifecycleGroup Map
		Map<Integer, LifecycleGroup> phases = new TreeMap<>();
// 遍历 lifecycleBeans
		lifecycleBeans.forEach((beanName, bean) -> {
			//SmartLifecycle : 生命周期接口的扩展,用于那些需要在 ApplicationContext 刷新 和/或 特定顺序关闭时 启动的对象。
			//如果不是 autoStartupOnly || (bean 是 SmartLifecycle 的实例) && bean指定在包含ApplicationContext的刷新时由容器自动启动
			if (!autoStartupOnly || (bean instanceof SmartLifecycle smartLifecycle && smartLifecycle.isAutoStartup())) {
				//获取 bean 的生命周期阶段(相位值)
				int phase = getPhase(bean);
				//将 phase，group 绑定到 phases 中
				phases.computeIfAbsent(
						phase,
						p -> new LifecycleGroup(phase, this.timeoutPerShutdownPhase, lifecycleBeans, autoStartupOnly)
				).add(beanName, bean);
			}
		});
		// 如果 phases 不是空集
		if (!phases.isEmpty()) {
			// 启动 key 对应的 LifecycleGroup
			phases.values().forEach(LifecycleGroup::start);
		}
	}

	/**
	 * <p>作为 lifecycleBeans 的一部分启动 dependency 对应 Lifecycle Bean对象，确保它所依赖的任何bean都首先启动。</p>
	 * Start the specified bean as part of the given set of Lifecycle beans,
	 * making sure that any beans that it depends on are started first.
	 * <p>作为给定生命周期bean集合的一部分启动指定的bean，确保它所依赖的任何bean都首先启动。</p>
	 *
	 * @param lifecycleBeans  a Map with bean name as key and Lifecycle instance as value
	 *                        --  一个以bean名称为键、以生命周期实例为值的映射
	 * @param beanName        the name of the bean to start
	 *                        -- 要启动的Bean名称
	 * @param autoStartupOnly 是否只包含自动启动的标记
	 */
	private void doStart(Map<String, ? extends Lifecycle> lifecycleBeans, String beanName, boolean autoStartupOnly) {
		//移除在 lifecycleBeans 中的 beanName 映射关系，因为每个 Lifecycle 对象只能执行一次
		Lifecycle bean = lifecycleBeans.remove(beanName);
		//如果 bean 不为 null && bean不是当前对象
		if (bean != null && bean != this) {
			//获取 beanName 所依赖的所有bean的名称
			String[] dependenciesForBean = getBeanFactory().getDependenciesForBean(beanName);
			//遍历 dependenciesForBean
			for (String dependency : dependenciesForBean) {
				//递归本方法，作为lifecycleBeans的一部分启动dependency对应Lifecycle Bean对象，确保它所依赖的任何bean都首先启动。
				doStart(lifecycleBeans, dependency, autoStartupOnly);
			}
			//如果 bean 不是运行中 && ( 不是只包含自动启动标记 || bean不是SmartLifecycle实例 || bean是在包含ApplicationContext
			// 		的刷新时由容器自动启动)
			if (!bean.isRunning() &&
					(!autoStartupOnly || !(bean instanceof SmartLifecycle smartLifecycle) || smartLifecycle.isAutoStartup())) {
				//如果当前日志级别是跟踪
				if (logger.isTraceEnabled()) {
					//打印跟踪日志：启动 类型 [bean类名] 的 bean 'beanName'
					logger.trace("Starting bean '" + beanName + "' of type [" + bean.getClass().getName() + "]");
				}
				try {
					//启动 bean
					bean.start();
				} catch (Throwable ex) { //捕捉 启动Bean时出现的异常
					//抛出 应用程序上下文异常：启动 bean 'beanName' 失败
					throw new ApplicationContextException("Failed to start bean '" + beanName + "'", ex);
				}
				//如果当前日志级别是调试
				if (logger.isDebugEnabled()) {
					//打印调试日志：成功启动 bean 'beanName'
					logger.debug("Successfully started bean '" + beanName + "'");
				}
			}
		}
	}

	private void stopBeans() {
		//检索所有适用的生命周期Bean：所有已经创建的单例Bean，以及所有 SmartLifeCycle Bean(即使它们被标记未延迟初始化)
		Map<String, Lifecycle> lifecycleBeans = getLifecycleBeans();
		//各 phases 所对应的 LifecycleGroup Map
		Map<Integer, LifecycleGroup> phases = new HashMap<>();
		// 遍历 lifecycleBeans
		lifecycleBeans.forEach((beanName, bean) -> {
			//获取 bean 的生命周期阶段(相位值)
			int shutdownPhase = getPhase(bean);
			//获取 phase 对应的 LifecycleGroup
			LifecycleGroup group = phases.get(shutdownPhase);
			//如果 group 不为 null
			if (group == null) {
				// 新建一个 LifecycleGroup 的实例
				group = new LifecycleGroup(shutdownPhase, this.timeoutPerShutdownPhase, lifecycleBeans, false);
				//将 phase，group 绑定到 phases 中
				phases.put(shutdownPhase, group);
			}
			//将beanName，bean绑定到 group 中
			group.add(beanName, bean);
		});
		// 如果 phases 不是空集
		if (!phases.isEmpty()) {
			//存放生命周期阶段(相位值)
			List<Integer> keys = new ArrayList<>(phases.keySet());
			//Collections.reverseOrder()：降序排序
			//排序，相位值越大越靠前
			keys.sort(Collections.reverseOrder());
			//遍历 keys
			for (Integer key : keys) {
				// 停止 key 对应的 LifecycleGroup
				phases.get(key).stop();
			}
		}
	}

	/**
	 * <p>停止lifecycleBeans 集合的一部分的dependency 对应 Lifecycle Bean对象，确保依赖于它的任何bean都首先停止。</p>
	 * Stop the specified bean as part of the given set of Lifecycle beans,
	 * making sure that any beans that depends on it are stopped first.
	 * <p>停止作为给定生命周期bean集合的一部分的指定bean，确保依赖于它的任何bean都首先停止。</p>
	 *
	 * @param lifecycleBeans     a Map with bean name as key and Lifecycle instance as value
	 *                           -- 一个以bean名称为键、以生命周期实例为值的映射
	 * @param beanName           the name of the bean to stop
	 *                           -- 要停止bean的名称
	 * @param latch              一个计数锁，每处理一个SmartLifecycle Bean对象就-1
	 * @param countDownBeanNames 即将处理的 SmartLifecycle Bean对象的Bean名
	 */
	private void doStop(Map<String, ? extends Lifecycle> lifecycleBeans, final String beanName,
						final CountDownLatch latch, final Set<String> countDownBeanNames) {
		//移除在 lifecycleBeans 中的 beanName 映射关系，因为每个 Lifecycle 对象只能执行一次
		Lifecycle bean = lifecycleBeans.remove(beanName);
		//如果 bean 不为 null
		if (bean != null) {
			//获取 beanName 所依赖的所有bean的名称
			String[] dependentBeans = getBeanFactory().getDependentBeans(beanName);
			//遍历 dependenciesForBean
			for (String dependentBean : dependentBeans) {
				//递归本方法，作为lifecycleBeans的一部分启动dependency对应Lifecycle Bean对象，确保它所依赖的任何bean都首先启动。
				doStop(lifecycleBeans, dependentBean, latch, countDownBeanNames);
			}
			try {
				//如果 bean 是运行中
				if (bean.isRunning()) {
					//如果bean是SmartLifecycle实例
					if (bean instanceof SmartLifecycle smartLifecycle) {
						//如果当前日志级别是跟踪
						if (logger.isTraceEnabled()) {
							//打印跟踪日志：询问 类型 [bean类名] 的bean'beanName'执行停止
							logger.trace("Asking bean '" + beanName + "' of type [" +
									bean.getClass().getName() + "] to stop");
						}
						//将 beanName 添加 countDownNames
						countDownBeanNames.add(beanName);
						//指示当前正在运行的生命周期组件必须停止。
						smartLifecycle.stop(() -> {
							latch.countDown();
							//移除在 countDownBeanNames 中的 beanName
							countDownBeanNames.remove(beanName);
							//如果当前日志级别是调试
							if (logger.isDebugEnabled()) {
								//打印调试日志 ：Bean 'beanName' 完成了它的停止过程
								logger.debug("Bean '" + beanName + "' completed its stop procedure");
							}
						});
					} else {
						//如果当前日志级别是跟踪
						if (logger.isTraceEnabled()) {
							//打印跟踪日志：询问 类型 [bean类名] 的bean'beanName'执行停止
							logger.trace("Stopping bean '" + beanName + "' of type [" +
									bean.getClass().getName() + "]");
						}
						//通常以同步方式停止此组件。以使该组件在返回方法后完全停止。
						bean.stop();
						//如果当前日志级别是调试
						if (logger.isDebugEnabled()) {
							//打印调试日志 ：成功停止 Bean 'beanName'
							logger.debug("Successfully stopped bean '" + beanName + "'");
						}
					}
				}
				//如果bean是SmartLifecycle，且该Bean并没有运行

				else if (bean instanceof SmartLifecycle) {
					// Don't wait for beans that aren't running...
					// 不要等待没有运行的Bean
					latch.countDown();
				}
			}
			//捕捉停止Bean的抛出的所有异常
			catch (Throwable ex) {
				if (logger.isWarnEnabled()) {
					//打印调试日志 ：失败停止 Bean 'beanName'
					logger.warn("Failed to stop bean '" + beanName + "'", ex);
				}
			}
		}
	}


	// overridable hooks

	/**
	 * Retrieve all applicable Lifecycle beans: all singletons that have already been created,
	 * as well as all SmartLifecycle beans (even if they are marked as lazy-init).
	 * <p>检索所有适用的生命周期Bean：所有已经创建的单例Bean，以及所有 SmartLifeCycle Bean(即使它们被标记未延迟初始化)</p>
	 *
	 * @return the Map of applicable beans, with bean names as keys and bean instances as values
	 * -- 可以Bean的映射，将Bean名称作为键，将Bean实例作为值
	 */
	protected Map<String, Lifecycle> getLifecycleBeans() {
		//获取当前 BeanFactory
		ConfigurableListableBeanFactory beanFactory = getBeanFactory();
		Map<String, Lifecycle> beans = new LinkedHashMap<>();
		//获取与 Lifecycle（包括子类）匹配的bean名称，如果是FactoryBeans会根据beanDefinition 或getObjectType的值判断
		String[] beanNames = beanFactory.getBeanNamesForType(Lifecycle.class, false, false);
		//遍历 beanNames
		for (String beanName : beanNames) {
			//去除开头的'&'字符，返回剩余的字符串作为转换后的Bean名称【可能是全类名】
			String beanNameToRegister = BeanFactoryUtils.transformedBeanName(beanName);
			//确定具有 beanNameToRegister 的Bean是否为FactoryBean
			boolean isFactoryBean = beanFactory.isFactoryBean(beanNameToRegister);
			//如果是 FactoryBean 就对 beanName 加上'&'作为前缀 作为要检查的BeanName
			String beanNameToCheck = (isFactoryBean ? BeanFactory.FACTORY_BEAN_PREFIX + beanName : beanName);
			//如果 beanFactory 包含 beanNameToRegister 的单例实例 &&
			// 		(不是 FactoryBean || beanNameToCheck 在 beanFactory 的 Bean 对象 与 Lifecycle 类型匹配
			// 			|| beanNameToCheck 在 beanFactory 的 Bean 对象 与 SmartLifecycle 类型匹配)
			if ((beanFactory.containsSingleton(beanNameToRegister) &&
					(!isFactoryBean || matchesBeanType(Lifecycle.class, beanNameToCheck, beanFactory))) ||
					matchesBeanType(SmartLifecycle.class, beanNameToCheck, beanFactory)) {
				//获取在BeanFactory中 beanNameToCheck 对应的Bean对象
				Object bean = beanFactory.getBean(beanNameToCheck);
				//如果 bean 不是当前对象 && bean 是 Lifecycle 的实例
				if (bean != this && bean instanceof Lifecycle) {
					//将 beanNameToRegister,bean 绑定到 beans 中
					beans.put(beanNameToRegister, (Lifecycle) bean);
				}
			}
		}
		// 返回 beans
		return beans;
	}

	/**
	 * 确定 beanName 在 beanFactory 的 Bean 对象 是否与 targetType 类型匹配
	 *
	 * @param targetType  目标类型
	 * @param beanName    bean名
	 * @param beanFactory bean工厂
	 * @return 是否匹配
	 */
	private boolean matchesBeanType(Class<?> targetType, String beanName, BeanFactory beanFactory) {
		// 从 beanFactory 中获取 beanName 的bean类型
		Class<?> beanType = beanFactory.getType(beanName);
		// 如果 beanType 不为 null && beanType 不是 targetType 的子类或实现
		return (beanType != null && targetType.isAssignableFrom(beanType));
	}

	/**
	 * Determine the lifecycle phase of the given bean.
	 * <p>确定给定bean的生命周期阶段(相位值)。</p>
	 * <p>The default implementation checks for the {@link Phased} interface, using
	 * a default of 0 otherwise. Can be overridden to apply other/further policies.
	 * <p>默认实现检查phase接口，否则使用默认值0。可被重写以应用其他/进一步的策略。</p>
	 *
	 * @param bean the bean to introspect
	 *             -- 需要自省的Bean
	 * @return the phase (an integer value) -- 相位值(一个整数值)
	 * @see Phased#getPhase()
	 * @see SmartLifecycle
	 */
	protected int getPhase(Lifecycle bean) {
		//如果 bean 是 Phased 的实例，就返回其指定的相位值;否则默认返回0
		return (bean instanceof Phased ? ((Phased) bean).getPhase() : 0);
	}


	/**
	 * Helper class for maintaining a group of Lifecycle beans that should be started
	 * and stopped together based on their 'phase' value (or the default value of 0).
	 * <p>用于维护一组 生命周期Bean 的Helper 类，应该根据它们'phase'值(或默认值0)一起启动和
	 * 停止这些Bean</p>
	 */
	private class LifecycleGroup {

		/**
		 *  生命周期阶段(相位值)
		 */
		private final int phase;

		/**
		 * 指定为任何阶段(具有相同'phase'值的 SmartLifecycle Bean组) 的关闭分配的最大 时间(以毫秒为单位)
		 */
		private final long timeout;

		/**
		 * beanFactory中 所有的Lifecycle Bean 对象映射，key为 Bean名，value为 Lifecycle Bean 对象
		 */
		private final Map<String, ? extends Lifecycle> lifecycleBeans;

		/**
		 * 是否只包含自动启动的标记
		 */
		private final boolean autoStartupOnly;

		/**
		 * 与 phase 对应的 LifecycleGroupMember 对象集合
		 */
		private final List<LifecycleGroupMember> members = new ArrayList<>();

		/**
		 * 该 LifecycleGroup 所含有的 SmartLifecycle 数
		 */
		private int smartMemberCount;

		/**
		 * 新建一个 LifecycleGroup 对象
		 * @param phase  生命周期阶段(相位值)
		 * @param timeout 指定为任何阶段(具有相同'phase'值的 SmartLifecycle Bean组)
		 *                      的关闭分配的最大 时间(以毫秒为单位)
		 * @param lifecycleBeans 所有适用的生命周期Bean
		 * @param autoStartupOnly 是否只包含自动启动的标记
		 */
		public LifecycleGroup(
				int phase, long timeout, Map<String, ? extends Lifecycle> lifecycleBeans, boolean autoStartupOnly) {

			this.phase = phase;
			this.timeout = timeout;
			this.lifecycleBeans = lifecycleBeans;
			this.autoStartupOnly = autoStartupOnly;
		}

		/**
		 * 添加 LifecycleGroupMember
		 * @param name  Bean 名
		 * @param bean name 对应的 Lifecycle Bean 对象
		 */
		public void add(String name, Lifecycle bean) {
			//使用name,bean 构建出一个 LifecycleGroupMember 对象。然后添加到 members 中
			this.members.add(new LifecycleGroupMember(name, bean));
			//如果 bean 是 SmartLifecycle 的实例
			if (bean instanceof SmartLifecycle) {
				//该 LifecycleGroup 所含有的 SmartLifecycle 数累计1
				this.smartMemberCount++;
			}
		}

		public void start() {
			//如果 members是空集
			if (this.members.isEmpty()) {
				//结束方法
				return;
			}
			//如果当前日志是debug模式
			if (logger.isDebugEnabled()) {
				//打印debug日志：开始 生命周期阶段(相位值) 为 phase 的 Lifecycle Bean 对象
				logger.debug("Starting beans in phase " + this.phase);
			}
			//对 members 进行排序，生命周期阶段(相位值)越小越靠前
			Collections.sort(this.members);
			//遍历 members
			for (LifecycleGroupMember member : this.members) {
				doStart(this.lifecycleBeans, member.name, this.autoStartupOnly);
			}
		}

		public void stop() {
			//如果 members是空集
			if (this.members.isEmpty()) {
				//结束方法
				return;
			}
			//如果当前日志是debug模式
			if (logger.isDebugEnabled()) {
				//打印debug日志：结束 生命周期阶段(相位值) 为 phase 的 Lifecycle Bean 对象
				logger.debug("Stopping beans in phase " + this.phase);
			}
			//对 members 进行排序，生命周期阶段(相位值)越大越靠前
			this.members.sort(Collections.reverseOrder());
			//新建一个计数锁，每处理一个SmartLifecycle Bean对象就-1
			CountDownLatch latch = new CountDownLatch(this.smartMemberCount);
			//Collections.synchronizedSet：用于返回一个同步的(线程安全的)有序set由指定的有序set支持。
			//存放即将处理的 SmartLifecycle Bean对象的Bean名
			Set<String> countDownBeanNames = Collections.synchronizedSet(new LinkedHashSet<>());
			//存放 beanFactory中 所有的Lifecycle Bean 对象的Bean名
			Set<String> lifecycleBeanNames = new HashSet<>(this.lifecycleBeans.keySet());
			//遍历 members
			for (LifecycleGroupMember member : this.members) {
				//如果 lifecycleBeanNames 包含 member.name 的 Bean 对象
				if (lifecycleBeanNames.contains(member.name)) {
					//停止lifecycleBeans 集合的一部分的dependency 对应 Lifecycle Bean对象，确保依赖于它的任何bean都首先停止。
					doStop(this.lifecycleBeans, member.name, latch, countDownBeanNames);
				}
				//如果 member.bean 是 SmartLifecycle 实例 && lifecycleBeanNames没有该 member.name 的 Bean 对象【这意味这该member.bean已经被处理了】
				else if (member.bean instanceof SmartLifecycle) {
					// Already removed: must have been a dependent bean from another phase
					// 已经删除:必须是来自另一个阶段的依赖bean
					//latch -1
					latch.countDown();
				}
			}
			try {
				//等待所有 SmartLifecycle Bean 对象 都 完成停止工作。如果超过 timeout将不再等待
				latch.await(this.timeout, TimeUnit.MILLISECONDS);
				//如果当前 latch不为0 && countDownBeanNames不为空集 && 当前日志级别为info
				if (latch.getCount() > 0 && !countDownBeanNames.isEmpty() && logger.isInfoEnabled()) {
					//打印info级别日志：失败关闭 countDownBeanNames.size Bean 在生命周期阶段(相位值)为phase中，在超时时间为timeout：countDownBeanNames
					logger.info("Failed to shut down " + countDownBeanNames.size() + " bean" +
							(countDownBeanNames.size() > 1 ? "s" : "") + " with phase value " +
							this.phase + " within timeout of " + this.timeout + ": " + countDownBeanNames);
				}
			}
			catch (InterruptedException ex) {//捕捉终端异常
				//将当前线程中断
				Thread.currentThread().interrupt();
			}
		}
	}


	/**
	 * Adapts the Comparable interface onto the lifecycle phase model.
	 * <p>将可比较的接口调整到生命周期阶段模型上。</p>
	 */
	private class LifecycleGroupMember implements Comparable<LifecycleGroupMember> {

		/**
		 * Bean 名称
		 */
		private final String name;

		/**
		 * name 对应的 Lifecycle Bean 对象
		 */
		private final Lifecycle bean;

		/**
		 * 新建一个  LifecycleGroupMember 对象
		 * @param name Bean 名
		 * @param bean  name 对应的 Lifecycle Bean 对象
		 */
		LifecycleGroupMember(String name, Lifecycle bean) {
			this.name = name;
			this.bean = bean;
		}

		@Override
		public int compareTo(LifecycleGroupMember other) {
			//获取 bean 的生命周期阶段(相位值)。
			int thisPhase = getPhase(this.bean);
			//获取 获取 bean 的生命周期阶段(相位值)。 的生命周期阶段(相位值)。
			int otherPhase = getPhase(other.bean);
			// 比较两者之间的相位值
			return Integer.compare(thisPhase, otherPhase);
		}
	}

}
