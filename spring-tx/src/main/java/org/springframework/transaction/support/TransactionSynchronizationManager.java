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

package org.springframework.transaction.support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.core.NamedThreadLocal;
import org.springframework.core.OrderComparator;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;

/**
 * Central delegate that manages resources and transaction synchronizations per thread.
 * To be used by resource management code but not by typical application code.
 *
 * <p>Supports one resource per key without overwriting, that is, a resource needs
 * to be removed before a new one can be set for the same key.
 * Supports a list of transaction synchronizations if synchronization is active.
 *
 * <p>Resource management code should check for thread-bound resources, e.g. JDBC
 * Connections or Hibernate Sessions, via {@code getResource}. Such code is
 * normally not supposed to bind resources to threads, as this is the responsibility
 * of transaction managers. A further option is to lazily bind on first use if
 * transaction synchronization is active, for performing transactions that span
 * an arbitrary number of resources.
 *
 * <p>Transaction synchronization must be activated and deactivated by a transaction
 * manager via {@link #initSynchronization()} and {@link #clearSynchronization()}.
 * This is automatically supported by {@link AbstractPlatformTransactionManager},
 * and thus by all standard Spring transaction managers, such as
 * {@link org.springframework.transaction.jta.JtaTransactionManager} and
 * {@link org.springframework.jdbc.datasource.DataSourceTransactionManager}.
 *
 * <p>Resource management code should only register synchronizations when this
 * manager is active, which can be checked via {@link #isSynchronizationActive};
 * it should perform immediate resource cleanup else. If transaction synchronization
 * isn't active, there is either no current transaction, or the transaction manager
 * doesn't support transaction synchronization.
 *
 * <p>Synchronization is for example used to always return the same resources
 * within a JTA transaction, e.g. a JDBC Connection or a Hibernate Session for
 * any given DataSource or SessionFactory, respectively.
 *
 * @author Juergen Hoeller
 * @see #isSynchronizationActive
 * @see #registerSynchronization
 * @see TransactionSynchronization
 * @see AbstractPlatformTransactionManager#setTransactionSynchronization
 * @see org.springframework.transaction.jta.JtaTransactionManager
 * @see org.springframework.jdbc.datasource.DataSourceTransactionManager
 * @see org.springframework.jdbc.datasource.DataSourceUtils#getConnection
 * @since 02.06.2003
 */
public abstract class TransactionSynchronizationManager {

	/**
	 * 事务资源
	 * <p>
	 * 一个ThreadLocal属性，用于存放线程当前使用的数据库资源
	 * value是一个Map<Object, Object>，key为某个数据源DataSource ，value实际上就是连接ConnectionHolder
	 */
	private static final ThreadLocal<Map<Object, Object>> resources =
			new NamedThreadLocal<>("Transactional resources");


	/**
	 * 事务同步
	 * <p>
	 * 一个ThreadLocal属性，用于存放线程当前激活的事务同步器TransactionSynchronization
	 * 每个线程都可以开启多个事物同步，用于在处理事务的各个阶段进行自定义扩展或者回调
	 * <p>
	 * TransactionSynchronization的同步回调功能类似于此前学习的@TransactionalEventListener
	 */
	private static final ThreadLocal<Set<TransactionSynchronization>> synchronizations =
			new NamedThreadLocal<>("Transaction synchronizations");

	/**
	 * 当前事务的名称
	 * <p>
	 * 一个ThreadLocal属性，用于存放线程当前的事务的名称
	 */
	private static final ThreadLocal<String> currentTransactionName =
			new NamedThreadLocal<>("Current transaction name");

	/**
	 * 当前事务的只读状态
	 * <p>
	 * 一个ThreadLocal属性，用于存放线程当前的事务的只读状态
	 */
	private static final ThreadLocal<Boolean> currentTransactionReadOnly =
			new NamedThreadLocal<>("Current transaction read-only status");

	/**
	 * 当前事务的隔离级别
	 * <p>
	 * 一个ThreadLocal属性，用于存放线程当前的当前事务的隔离级别
	 */
	private static final ThreadLocal<Integer> currentTransactionIsolationLevel =
			new NamedThreadLocal<>("Current transaction isolation level");

	/**
	 * 当前事务是否开启
	 * <p>
	 * 一个ThreadLocal属性，用于存放线程当前是否开启了事务
	 */
	private static final ThreadLocal<Boolean> actualTransactionActive =
			new NamedThreadLocal<>("Actual transaction active");


	//-------------------------------------------------------------------------
	// Management of transaction-associated resource handles
	//-------------------------------------------------------------------------

	/**
	 * Return all resources that are bound to the current thread.
	 * <p>Mainly for debugging purposes. Resource managers should always invoke
	 * {@code hasResource} for a specific resource key that they are interested in.
	 *
	 * @return a Map with resource keys (usually the resource factory) and resource
	 * values (usually the active resource object), or an empty Map if there are
	 * currently no resources bound
	 * @see #hasResource
	 */
	public static Map<Object, Object> getResourceMap() {
		Map<Object, Object> map = resources.get();
		return (map != null ? Collections.unmodifiableMap(map) : Collections.emptyMap());
	}

	/**
	 * Check if there is a resource for the given key bound to the current thread.
	 *
	 * @param key the key to check (usually the resource factory)
	 * @return if there is a value bound to the current thread
	 * @see ResourceTransactionManager#getResourceFactory()
	 */
	public static boolean hasResource(Object key) {
		Object actualKey = TransactionSynchronizationUtils.unwrapResourceIfNecessary(key);
		Object value = doGetResource(actualKey);
		return (value != null);
	}

	/**
	 * Retrieve a resource for the given key that is bound to the current thread.
	 *
	 * @param key the key to check (usually the resource factory)
	 * @return a value bound to the current thread (usually the active
	 * resource object), or {@code null} if none
	 * @see ResourceTransactionManager#getResourceFactory()
	 */
	@Nullable
	public static Object getResource(Object key) {
		Object actualKey = TransactionSynchronizationUtils.unwrapResourceIfNecessary(key);
		return doGetResource(actualKey);
	}

	/**
	 * Actually check the value of the resource that is bound for the given key.
	 * 实际检查给定key绑定的资源的value值。
	 *
	 * @return 给定key绑定的资源的value值，如果没找到就返回null
	 */
	@Nullable
	private static Object doGetResource(Object actualKey) {
		//获取和当前线程绑定的数据库事务资源map
		Map<Object, Object> map = resources.get();
		//如果map为null，说明此前没有开启过事务，直接返回null
		if (map == null) {
			return null;
		}
		//对于DataSourceTransactionManager，就是
		//根据指定key（数据源DataSource）获取对应的连接ConnectionHolder
		Object value = map.get(actualKey);
		// Transparently remove ResourceHolder that was marked as void...
		//删除无效的ResourceHolder
		if (value instanceof ResourceHolder resourceHolder && resourceHolder.isVoid()) {
			map.remove(actualKey);
			// Remove entire ThreadLocal if empty...
			if (map.isEmpty()) {
				resources.remove();
			}
			value = null;
		}
		return value;
	}

	/**
	 * Bind the given resource for the given key to the current thread.
	 * 将给定key的给定资源value绑定到当前线程。
	 * 对于DataSourceTransactionManager，key就是DataSource实例，value就是ConnectionHolder
	 *
	 * @param key   the key to bind the value to (usually the resource factory)
	 * 将值绑定到的键（通常是资源工厂，比如dataSource）
	 * @param value the value to bind (usually the active resource object)
	 * 要绑定的值（通常是活动资源对象，比如数据库连接）
	 * @throws IllegalStateException if there is already a value bound to the thread
	 * 如果已经有绑定到线程的值
	 * @see ResourceTransactionManager#getResourceFactory()
	 */
	public static void bindResource(Object key, Object value) throws IllegalStateException {
		Object actualKey = TransactionSynchronizationUtils.unwrapResourceIfNecessary(key);
		Assert.notNull(value, "Value must not be null");
		//获取当前线程的本地资源map
		Map<Object, Object> map = resources.get();
		// set ThreadLocal Map if none found
		//如果找不到，则设置一个Map
		if (map == null) {
			map = new HashMap<>();
			resources.set(map);
		}
		//将actualKey和value存入map中，返回旧的value
		Object oldValue = map.put(actualKey, value);
		// Transparently suppress a ResourceHolder that was marked as void...
		if (oldValue instanceof ResourceHolder resourceHolder && resourceHolder.isVoid()) {
			oldValue = null;
		}
		//如果已经有绑定到线程的当前key的值，则抛出异常
		if (oldValue != null) {
			throw new IllegalStateException(
					"Already value [" + oldValue + "] for key [" + actualKey + "] bound to thread");
		}
	}

	/**
	 * Unbind a resource for the given key from the current thread.
	 * 移除当前线程中给定key绑定的资源的值
	 *
	 * @param key the key to unbind (usually the resource factory)
	 * @return the previously bound value (usually the active resource object)
	 * @throws IllegalStateException if there is no value bound to the thread
	 * @see ResourceTransactionManager#getResourceFactory()
	 */
	public static Object unbindResource(Object key) throws IllegalStateException {
		Object actualKey = TransactionSynchronizationUtils.unwrapResourceIfNecessary(key);
		//真正的移除指定的key对应的连接
		Object value = doUnbindResource(actualKey);
		if (value == null) {
			throw new IllegalStateException("No value for key [" + actualKey + "] bound to thread");
		}
		return value;
	}

	/**
	 * Unbind a resource for the given key from the current thread.
	 *
	 * @param key the key to unbind (usually the resource factory)
	 * @return the previously bound value, or {@code null} if none bound
	 */
	@Nullable
	public static Object unbindResourceIfPossible(Object key) {
		Object actualKey = TransactionSynchronizationUtils.unwrapResourceIfNecessary(key);
		return doUnbindResource(actualKey);
	}

	/**
	 * Actually remove the value of the resource that is bound for the given key.
	 * 移除当前线程中给定key绑定的资源的值。
	 */
	@Nullable
	private static Object doUnbindResource(Object actualKey) {
		//获取和当前线程绑定的数据库资源map
		Map<Object, Object> map = resources.get();
		if (map == null) {
			return null;
		}
		//从map中移除从当前数据源对应的连接缓存
		Object value = map.remove(actualKey);
		// Remove entire ThreadLocal if empty...
		//如果map为空，则删除整个ThreadLocal。
		if (map.isEmpty()) {
			resources.remove();
		}
		// Transparently suppress a ResourceHolder that was marked as void...
		if (value instanceof ResourceHolder resourceHolder && resourceHolder.isVoid()) {
			value = null;
		}
		return value;
	}

	//-------------------------------------------------------------------------
	// Management of transaction synchronizations
	//-------------------------------------------------------------------------

	/**
	 * Return if transaction synchronization is active for the current thread.
	 * Can be called before register to avoid unnecessary instance creation.
	 *
	 * @see #registerSynchronization
	 */
	public static boolean isSynchronizationActive() {
		return (synchronizations.get() != null);
	}

	/**
	 * Activate transaction synchronization for the current thread.
	 * Called by a transaction manager on transaction begin.
	 * 激活当前线程的事务同步。由事务管理器在事务开始时调用。
	 *
	 * @throws IllegalStateException if synchronization is already active
	 */
	public static void initSynchronization() throws IllegalStateException {
		//如果同步已处于活动状态，即synchronizations保存的线程本地变量不为null，则抛出异常
		if (isSynchronizationActive()) {
			throw new IllegalStateException("Cannot activate transaction synchronization - already active");
		}
		//否则就为当前初始化一个线程本地变量，这是一个空的LinkedHashSet
		//虽然没有任何的TransactionSynchronization，但是已经不为null了
		synchronizations.set(new LinkedHashSet<>());
	}

	/**
	 * Register a new transaction synchronization for the current thread.
	 * Typically called by resource management code.
	 * <p>Note that synchronizations can implement the
	 * {@link org.springframework.core.Ordered} interface.
	 * They will be executed in an order according to their order value (if any).
	 *
	 * @param synchronization the synchronization object to register
	 * @throws IllegalStateException if transaction synchronization is not active
	 * @see org.springframework.core.Ordered
	 */
	public static void registerSynchronization(TransactionSynchronization synchronization)
			throws IllegalStateException {

		Assert.notNull(synchronization, "TransactionSynchronization must not be null");
		Set<TransactionSynchronization> synchs = synchronizations.get();
		if (synchs == null) {
			throw new IllegalStateException("Transaction synchronization is not active");
		}
		synchs.add(synchronization);
	}

	/**
	 * Return an unmodifiable snapshot list of all registered synchronizations
	 * for the current thread.
	 * 调用该方法时一定要保证当前线程存在事务同步，否则将抛出异常,因此需要先调用isSynchronizationActive方法来校验
	 * 返回当前线程的所有已注册的事务同步的无法修改的快照列表
	 *
	 * @return unmodifiable List of TransactionSynchronization instances
	 * 无法修改的TransactionSynchronization实例列表
	 * @throws IllegalStateException if synchronization is not active
	 * @see TransactionSynchronization
	 */
	public static List<TransactionSynchronization> getSynchronizations() throws IllegalStateException {
		//获取当前线程的事务同步列表
		Set<TransactionSynchronization> synchs = synchronizations.get();
		//为null就抛出IllegalStateException异常
		if (synchs == null) {
			throw new IllegalStateException("Transaction synchronization is not active");
		}
		// 返回不可修改的快照，以避免在迭代和调用可能进一步注册同步的同步回调时抛出ConcurrentModificationExceptions。
		if (synchs.isEmpty()) {
			return Collections.emptyList();
		} else {
			//在获取的之后对快照进行排序
			List<TransactionSynchronization> sortedSynchs = new ArrayList<>(synchs);
			AnnotationAwareOrderComparator.sort(sortedSynchs);
			return Collections.unmodifiableList(sortedSynchs);
		}
	}

	/**
	 * Deactivate transaction synchronization for the current thread.
	 * Called by the transaction manager on transaction cleanup.
	 * 调用该方法时一定要保证当前线程存在事务同步，否则将抛出异常
	 * 停用当前线程的事务同步，由事务管理器在事务清理中调用。
	 * @throws IllegalStateException if synchronization is not active
	 */
	public static void clearSynchronization() throws IllegalStateException {
		//如果没有激活事务同步，同样抛出异常
		if (!isSynchronizationActive()) {
			throw new IllegalStateException("Cannot deactivate transaction synchronization - not active");
		}
		//移除当前线程绑定到synchronizations属性的值
		synchronizations.remove();
	}


	//-------------------------------------------------------------------------
	// Exposure of transaction characteristics
	//-------------------------------------------------------------------------

	/**
	 * Expose the name of the current transaction, if any.
	 * Called by the transaction manager on transaction begin and on cleanup.
	 *
	 * @param name the name of the transaction, or {@code null} to reset it
	 * @see org.springframework.transaction.TransactionDefinition#getName()
	 */
	public static void setCurrentTransactionName(@Nullable String name) {
		currentTransactionName.set(name);
	}

	/**
	 * Return the name of the current transaction, or {@code null} if none set.
	 * To be called by resource management code for optimizations per use case,
	 * for example to optimize fetch strategies for specific named transactions.
	 *
	 * @see org.springframework.transaction.TransactionDefinition#getName()
	 */
	@Nullable
	public static String getCurrentTransactionName() {
		return currentTransactionName.get();
	}

	/**
	 * Expose a read-only flag for the current transaction.
	 * Called by the transaction manager on transaction begin and on cleanup.
	 *
	 * @param readOnly {@code true} to mark the current transaction
	 *                 as read-only; {@code false} to reset such a read-only marker
	 * @see org.springframework.transaction.TransactionDefinition#isReadOnly()
	 */
	public static void setCurrentTransactionReadOnly(boolean readOnly) {
		currentTransactionReadOnly.set(readOnly ? Boolean.TRUE : null);
	}

	/**
	 * Return whether the current transaction is marked as read-only.
	 * To be called by resource management code when preparing a newly
	 * created resource (for example, a Hibernate Session).
	 * <p>Note that transaction synchronizations receive the read-only flag
	 * as argument for the {@code beforeCommit} callback, to be able
	 * to suppress change detection on commit. The present method is meant
	 * to be used for earlier read-only checks, for example to set the
	 * flush mode of a Hibernate Session to "FlushMode.MANUAL" upfront.
	 *
	 * @see org.springframework.transaction.TransactionDefinition#isReadOnly()
	 * @see TransactionSynchronization#beforeCommit(boolean)
	 */
	public static boolean isCurrentTransactionReadOnly() {
		return (currentTransactionReadOnly.get() != null);
	}

	/**
	 * Expose an isolation level for the current transaction.
	 * Called by the transaction manager on transaction begin and on cleanup.
	 *
	 * @param isolationLevel the isolation level to expose, according to the
	 *                       JDBC Connection constants (equivalent to the corresponding Spring
	 *                       TransactionDefinition constants), or {@code null} to reset it
	 * @see java.sql.Connection#TRANSACTION_READ_UNCOMMITTED
	 * @see java.sql.Connection#TRANSACTION_READ_COMMITTED
	 * @see java.sql.Connection#TRANSACTION_REPEATABLE_READ
	 * @see java.sql.Connection#TRANSACTION_SERIALIZABLE
	 * @see org.springframework.transaction.TransactionDefinition#ISOLATION_READ_UNCOMMITTED
	 * @see org.springframework.transaction.TransactionDefinition#ISOLATION_READ_COMMITTED
	 * @see org.springframework.transaction.TransactionDefinition#ISOLATION_REPEATABLE_READ
	 * @see org.springframework.transaction.TransactionDefinition#ISOLATION_SERIALIZABLE
	 * @see org.springframework.transaction.TransactionDefinition#getIsolationLevel()
	 */
	public static void setCurrentTransactionIsolationLevel(@Nullable Integer isolationLevel) {
		currentTransactionIsolationLevel.set(isolationLevel);
	}

	/**
	 * Return the isolation level for the current transaction, if any.
	 * To be called by resource management code when preparing a newly
	 * created resource (for example, a JDBC Connection).
	 *
	 * @return the currently exposed isolation level, according to the
	 * JDBC Connection constants (equivalent to the corresponding Spring
	 * TransactionDefinition constants), or {@code null} if none
	 * @see java.sql.Connection#TRANSACTION_READ_UNCOMMITTED
	 * @see java.sql.Connection#TRANSACTION_READ_COMMITTED
	 * @see java.sql.Connection#TRANSACTION_REPEATABLE_READ
	 * @see java.sql.Connection#TRANSACTION_SERIALIZABLE
	 * @see org.springframework.transaction.TransactionDefinition#ISOLATION_READ_UNCOMMITTED
	 * @see org.springframework.transaction.TransactionDefinition#ISOLATION_READ_COMMITTED
	 * @see org.springframework.transaction.TransactionDefinition#ISOLATION_REPEATABLE_READ
	 * @see org.springframework.transaction.TransactionDefinition#ISOLATION_SERIALIZABLE
	 * @see org.springframework.transaction.TransactionDefinition#getIsolationLevel()
	 */
	@Nullable
	public static Integer getCurrentTransactionIsolationLevel() {
		return currentTransactionIsolationLevel.get();
	}

	/**
	 * Expose whether there currently is an actual transaction active.
	 * Called by the transaction manager on transaction begin and on cleanup.
	 *
	 * @param active {@code true} to mark the current thread as being associated
	 *               with an actual transaction; {@code false} to reset that marker
	 */
	public static void setActualTransactionActive(boolean active) {
		actualTransactionActive.set(active ? Boolean.TRUE : null);
	}

	/**
	 * Return whether there currently is an actual transaction active.
	 * This indicates whether the current thread is associated with an actual
	 * transaction rather than just with active transaction synchronization.
	 * <p>To be called by resource management code that wants to discriminate
	 * between active transaction synchronization (with or without backing
	 * resource transaction; also on PROPAGATION_SUPPORTS) and an actual
	 * transaction being active (with backing resource transaction;
	 * on PROPAGATION_REQUIRED, PROPAGATION_REQUIRES_NEW, etc).
	 *
	 * @see #isSynchronizationActive()
	 */
	public static boolean isActualTransactionActive() {
		return (actualTransactionActive.get() != null);
	}


	/**
	 * Clear the entire transaction synchronization state for the current thread:
	 * registered synchronizations as well as the various transaction characteristics.
	 * 清除当前线程的整个事务同步状态：已注册的同步以及各种事务特征。
	 *
	 * @see #clearSynchronization()
	 * @see #setCurrentTransactionName
	 * @see #setCurrentTransactionReadOnly
	 * @see #setCurrentTransactionIsolationLevel
	 * @see #setActualTransactionActive
	 */
	public static void clear() {
		//清除事务同步
		synchronizations.remove();
		//清除事务名
		currentTransactionName.remove();
		//清除事务只读状态
		currentTransactionReadOnly.remove();
		//清除事务隔离级别
		currentTransactionIsolationLevel.remove();
		//清除事务有效状态
		actualTransactionActive.remove();
	}

}
