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

package org.springframework.context.event;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.core.ResolvableType;
import org.springframework.lang.Nullable;
import org.springframework.util.ErrorHandler;

import java.util.concurrent.Executor;

/**
 * Simple implementation of the {@link ApplicationEventMulticaster} interface.
 * <p>简单实现的 ApplicationEventMulticaster 接口</p>
 * <p>Multicasts all events to all registered listeners, leaving it up to
 * the listeners to ignore events that they are not interested in.
 * Listeners will usually perform corresponding {@code instanceof}
 * checks on the passed-in event object.
 * <p>将所有事件多播给所有注册的监听器，让监听器忽略它们不感兴趣的事件。监听器通常
 * 会对传入的事件对象执行相应的 instanceof 检查</p>
 * <p>多播:一点对多点的通信</p>
 *
 * <p>By default, all listeners are invoked in the calling thread.
 * This allows the danger of a rogue listener blocking the entire application,
 * but adds minimal overhead. Specify an alternative task executor to have
 * listeners executed in different threads, for example from a thread pool.
 * <p>默认情况下，所有监听器都在调用线程中调用。这允许恶意监听器阻塞整个应用程序的
 * 危险，但只增加最小的开销。指定另一个任务执行器，让监听器在不同的线程中执行，例如
 * 从线程池中执行</p>
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @author Stephane Nicoll
 * @see #setTaskExecutor
 */
public class SimpleApplicationEventMulticaster extends AbstractApplicationEventMulticaster {

	/**
	 * 当前任务线程池
	 */
	@Nullable
	private Executor taskExecutor;

	@Nullable
	private ErrorHandler errorHandler;

	@Nullable
	private volatile Log lazyLogger;


	/**
	 * Create a new SimpleApplicationEventMulticaster.
	 * <p>创建一个新的 SimpleApplicationEventMulticaster </p>
	 */
	public SimpleApplicationEventMulticaster() {
	}

	/**
	 * Create a new SimpleApplicationEventMulticaster for the given BeanFactory.
	 * <p>为给定的BeanFactory创建一个新的 SimpleApplicationEventMulticaster </p>
	 */
	public SimpleApplicationEventMulticaster(BeanFactory beanFactory) {
		setBeanFactory(beanFactory);
	}


	/**
	 * Set a custom executor (typically a {@link org.springframework.core.task.TaskExecutor})
	 * to invoke each listener with.
	 * <p>Default is equivalent to {@link org.springframework.core.task.SyncTaskExecutor},
	 * executing all listeners synchronously in the calling thread.
	 * <p>Consider specifying an asynchronous task executor here to not block the
	 * caller until all listeners have been executed. However, note that asynchronous
	 * execution will not participate in the caller's thread context (class loader,
	 * transaction association) unless the TaskExecutor explicitly supports this.
	 *
	 * @see org.springframework.core.task.SyncTaskExecutor
	 * @see org.springframework.core.task.SimpleAsyncTaskExecutor
	 */
	public void setTaskExecutor(@Nullable Executor taskExecutor) {
		this.taskExecutor = taskExecutor;
	}

	/**
	 * Return the current task executor for this multicaster.
	 *  <p>返回此多播器的当前任务线程池</p>
	 */
	@Nullable
	protected Executor getTaskExecutor() {
		return this.taskExecutor;
	}

	/**
	 * Set the {@link ErrorHandler} to invoke in case an exception is thrown
	 * from a listener.
	 * <p>Default is none, with a listener exception stopping the current
	 * multicast and getting propagated to the publisher of the current event.
	 * If a {@linkplain #setTaskExecutor task executor} is specified, each
	 * individual listener exception will get propagated to the executor but
	 * won't necessarily stop execution of other listeners.
	 * <p>Consider setting an {@link ErrorHandler} implementation that catches
	 * and logs exceptions (a la
	 * {@link org.springframework.scheduling.support.TaskUtils#LOG_AND_SUPPRESS_ERROR_HANDLER})
	 * or an implementation that logs exceptions while nevertheless propagating them
	 * (e.g. {@link org.springframework.scheduling.support.TaskUtils#LOG_AND_PROPAGATE_ERROR_HANDLER}).
	 *
	 * @since 4.1
	 */
	public void setErrorHandler(@Nullable ErrorHandler errorHandler) {
		this.errorHandler = errorHandler;
	}

	/**
	 * Return the current error handler for this multicaster.
	 * <p>返回此多播器的当前错误处理程序</p>
	 * @since 4.1
	 */
	@Nullable
	protected ErrorHandler getErrorHandler() {
		return this.errorHandler;
	}

	@Override
	public void multicastEvent(ApplicationEvent event) {
		multicastEvent(event, resolveDefaultEventType(event));
	}

	/**
	 * 将给定的应用程序事件广播到到适当的监听器
	 *
	 * @param event     the event to multicast
	 * @param eventType the type of event (can be {@code null})
	 */
	@Override
	public void multicastEvent(ApplicationEvent event, @Nullable ResolvableType eventType) {
		ResolvableType type = (eventType != null ? eventType : resolveDefaultEventType(event));
		//获取任务执行器，SimpleApplicationEventMulticaster可以指定一个事件任务执行器和一个异常处理器，用于实现异步事件
		Executor executor = getTaskExecutor();
		/*
		 * 根据事件和事件类型获取可以支持该事件的监听器并依次进行调用，这里获取的监听器并不一定都会执行
		 */
		for (ApplicationListener<?> listener : getApplicationListeners(event, type)) {
			if (executor != null) {
				/*
				 * 如果执行器不为null，那么通过执行器异步的执行监听器的调用，默认就是null
				 */
				executor.execute(() -> invokeListener(listener, event));
			} else {
				/*
				 * 否则，直接在调用线程中执行监听器的调用，这样的话，实际上发布事件和接收事件并处理的线程就是同一个线程
				 * 许多开发者预期发布事件与接收事件并处理的操作是真正异步、解耦的，如果有这样的需求，则一定要注意这一点
				 * 当前如果不在这里设置执行器，在监听器方法上使用@Async注解也能实现异步事件处理，这是很常用的！
				 */
				invokeListener(listener, event);
			}
		}
	}

	private ResolvableType resolveDefaultEventType(ApplicationEvent event) {
		return ResolvableType.forInstance(event);
	}

	/**
	 * <p>回调listener的onApplicationEvent方法，传入 event</p>
	 * Invoke the given listener with the given event.
	 * <p>使用给定的事件调用给定的监听器</p>
	 * @param listener the ApplicationListener to invoke
	 *                 -- 要调用的ApplicationListenere
	 * @param event the current event to propagate
	 *              -- 要传播的当前事件
	 * @since 4.1
	 */
	protected void invokeListener(ApplicationListener<?> listener, ApplicationEvent event) {
		//获取此多播器的当前错误处理程序
		ErrorHandler errorHandler = getErrorHandler();
		//如果 errorHandler 不为 null
		if (errorHandler != null) {
			try {
				//回调listener的onApplicationEvent方法，传入 event
				doInvokeListener(listener, event);
			}
			catch (Throwable err) {
				//交给errorHandler接收处理 err
				errorHandler.handleError(err);
			}
		}
		else {
			//回调listener的onApplicationEvent方法，传入 event
			doInvokeListener(listener, event);
		}
	}

	/**
	 * 真正的调用listener的方法
	 *
	 * @param listener
	 * @param event
	 */
	@SuppressWarnings({"rawtypes", "unchecked"})
	private void doInvokeListener(ApplicationListener listener, ApplicationEvent event) {
		try {
			//调用listener的onApplicationEvent方法传播事件，这个方法就是处理事件的方法，不同的ApplicationListener有不同的实现
			//如果是ApplicationListenerMethodAdapter，即@EventListener方法监听器，那么首先会检验condition规则，只有符合规则才会真正的执行
			listener.onApplicationEvent(event);
		} catch (ClassCastException ex) { // 捕捉异常信息
			String msg = ex.getMessage();
			if (msg == null || matchesClassCastMessage(msg, event.getClass()) ||
					(event instanceof PayloadApplicationEvent payloadEvent &&
							matchesClassCastMessage(msg, payloadEvent.getPayload().getClass()))) {
				// Possibly a lambda-defined listener which we could not resolve the generic event type for
				// -> let's suppress the exception.
				Log loggerToUse = this.lazyLogger;
				if (loggerToUse == null) {
					loggerToUse = LogFactory.getLog(getClass());
					this.lazyLogger = loggerToUse;
				}
				if (loggerToUse.isTraceEnabled()) {
					loggerToUse.trace("Non-matching event type for listener: " + listener, ex);
				}
			} else {
				throw ex;
			}
		}
	}

	/**
	 * 匹配类转换消息，以保证抛出类转换异常是因eventClass引起的
	 * @param classCastMessage 类转换异常消息
	 * @param eventClass 事件类型
	 */
	private boolean matchesClassCastMessage(String classCastMessage, Class<?> eventClass) {
		// On Java 8, the message starts with the class name: "java.lang.String cannot be cast..."
		// 在 JAVA 8中，消息以类名开始：'java.lang.String 不能被转换 .. '
		//如果 classCastMessage 是以 eventClass类名开头，返回true
		if (classCastMessage.startsWith(eventClass.getName())) {
			//返回true
			return true;
		}
		// On Java 11, the message starts with "class ..." a.k.a. Class.toString()
		// 在 JAVA 11 中，消息是以'class ...' 开始，选择 Class.toString
		//如果 classCastMessage是以 eventClass.toString()开头，返回true
		if (classCastMessage.startsWith(eventClass.toString())) {
			return true;
		}
		// On Java 9, the message used to contain the module name: "java.base/java.lang.String cannot be cast..."
		// 在 Java 9, 用于包含模块名的消息：'java.base/java.lang.String 不能被转换'
		// 找出 classCastMessage 的 '/' 第一个索引位置
		int moduleSeparatorIndex = classCastMessage.indexOf('/');
		//如果找到了'/'位置 && '/'后面的字符串是以eventClass类名开头
		if (moduleSeparatorIndex != -1 && classCastMessage.startsWith(eventClass.getName(), moduleSeparatorIndex + 1)) {
			return true;
		}
		// Assuming an unrelated class cast failure...
		// 假设一个不相关的类转换失败
		return false;
	}

}
