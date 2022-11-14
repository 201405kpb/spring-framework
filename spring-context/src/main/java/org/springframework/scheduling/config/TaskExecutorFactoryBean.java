/*
 * Copyright 2002-2021 the original author or authors.
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

package org.springframework.scheduling.config;

import java.util.concurrent.RejectedExecutionHandler;

import org.springframework.beans.factory.BeanNameAware;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.task.TaskExecutor;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.util.StringUtils;

/**
 * {@link FactoryBean} for creating {@link ThreadPoolTaskExecutor} instances,
 * primarily used behind the XML task namespace.
 * 用于创建org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor实例的FactoryBean
 *
 * @author Mark Fisher
 * @author Juergen Hoeller
 * @since 3.0
 */
public class TaskExecutorFactoryBean implements
		FactoryBean<TaskExecutor>, BeanNameAware, InitializingBean, DisposableBean {

	@Nullable
	private String poolSize;

	@Nullable
	private Integer queueCapacity;

	@Nullable
	private RejectedExecutionHandler rejectedExecutionHandler;

	@Nullable
	private Integer keepAliveSeconds;

	@Nullable
	private String beanName;

	@Nullable
	private ThreadPoolTaskExecutor target;


	public void setPoolSize(String poolSize) {
		this.poolSize = poolSize;
	}

	public void setQueueCapacity(int queueCapacity) {
		this.queueCapacity = queueCapacity;
	}

	public void setRejectedExecutionHandler(RejectedExecutionHandler rejectedExecutionHandler) {
		this.rejectedExecutionHandler = rejectedExecutionHandler;
	}

	public void setKeepAliveSeconds(int keepAliveSeconds) {
		this.keepAliveSeconds = keepAliveSeconds;
	}

	@Override
	public void setBeanName(String beanName) {
		this.beanName = beanName;
	}


	/**
	 * 初始化回调方法，在TaskExecutorFactoryBean被实例化之后调用
	 * <p>
	 * 用于根据给定参数创建执行器
	 */
	@Override
	public void afterPropertiesSet() {
		//创建一个默认ThreadPoolTaskExecutor执行器
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		/*确定线程数，包括核心线程数与最大线程数*/
		determinePoolSizeRange(executor);
		//设置队列容量，如果存在
		if (this.queueCapacity != null) {
			executor.setQueueCapacity(this.queueCapacity);
		}
		//设置超时时间（秒），如果存在
		if (this.keepAliveSeconds != null) {
			executor.setKeepAliveSeconds(this.keepAliveSeconds);
		}
		//设置拒绝策略，如果存在
		if (this.rejectedExecutionHandler != null) {
			executor.setRejectedExecutionHandler(this.rejectedExecutionHandler);
		}
		//设置线程前缀为beanName，也就是<task:executor/>的id，如果没有指定id将会自动生成beanName
		if (this.beanName != null) {
			executor.setThreadNamePrefix(this.beanName + "-");
		}
		/*调用executor的afterPropertiesSet方法初始化执行器，因此我们不必手动调用*/
		executor.afterPropertiesSet();
		//赋值
		this.target = executor;
	}

	/**
	 * 确定线程数，包括核心线程数与最大线程数
	 *
	 * @param executor 执行器
	 */
	private void determinePoolSizeRange(ThreadPoolTaskExecutor executor) {
		//如果设置了poolSize值
		if (StringUtils.hasText(this.poolSize)) {
			try {
				int corePoolSize;
				int maxPoolSize;
				//首先尝试获取"-"的位置
				int separatorIndex = this.poolSize.indexOf('-');
				//如果不为-1，说明poolSize中存在"-"
				if (separatorIndex != -1) {
					//那么将"-"之前的部分数据解析为corePoolSize，将"-"之后的部分数据解析为maxPoolSize
					corePoolSize = Integer.parseInt(this.poolSize.substring(0, separatorIndex));
					maxPoolSize = Integer.parseInt(this.poolSize.substring(separatorIndex + 1, this.poolSize.length()));
					//如果核心线程数大于最大线程数，那么抛出异常
					if (corePoolSize > maxPoolSize) {
						throw new IllegalArgumentException(
								"Lower bound of pool-size range must not exceed the upper bound");
					}
					//如果没有设置队列容量，那么将会采用默认容量
					//ThreadPoolTaskExecutor默认队列容量为Integer.MAX_VALUE，相当于无界队列
					if (this.queueCapacity == null) {
						// No queue-capacity provided, so unbounded
						//并且如果核心线程数设置为0
						if (corePoolSize == 0) {
							// Actually set 'corePoolSize' to the upper bound of the range
							// but allow core threads to timeout...
							//那么允许核心线程超时，并且设置核心线程数等于最大线程数
							executor.setAllowCoreThreadTimeOut(true);
							corePoolSize = maxPoolSize;
						}
						//否则抛出异常，这说明如果poolSize采用"-"同时配置了核心线程与最大线程数，并且核心线程数不为0
						//那么queueCapacity必须设置值，因为默认的queueCapacity为Integer.MAX_VALUE，即无界队列
						else {
							// Non-zero lower bound implies a core-max size range...
							throw new IllegalArgumentException(
									"A non-zero lower bound for the size range requires a queue-capacity value");
						}
					}
				}
				//如果为-1，说明poolSize中不存在"-"，也就是说只设置了一个值
				//那么corePoolSize和maxPoolSize都设置为该值
				else {
					Integer value = Integer.valueOf(this.poolSize);
					corePoolSize = value;
					maxPoolSize = value;
				}
				executor.setCorePoolSize(corePoolSize);
				executor.setMaxPoolSize(maxPoolSize);
			} catch (NumberFormatException ex) {
				//如果是非数值类型，那么抛出异常
				throw new IllegalArgumentException("Invalid pool-size value [" + this.poolSize + "]: only single " +
						"maximum integer (e.g. \"5\") and minimum-maximum range (e.g. \"3-5\") are supported", ex);
			}
		}
	}


	@Override
	@Nullable
	public TaskExecutor getObject() {
		return this.target;
	}

	@Override
	public Class<? extends TaskExecutor> getObjectType() {
		return (this.target != null ? this.target.getClass() : ThreadPoolTaskExecutor.class);
	}

	@Override
	public boolean isSingleton() {
		return true;
	}


	@Override
	public void destroy() {
		if (this.target != null) {
			this.target.destroy();
		}
	}

}
