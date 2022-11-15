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

package org.springframework.transaction.support;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.core.Constants;
import org.springframework.lang.Nullable;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.InvalidTimeoutException;
import org.springframework.transaction.NestedTransactionNotSupportedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSuspensionNotSupportedException;
import org.springframework.transaction.UnexpectedRollbackException;

/**
 * Abstract base class that implements Spring's standard transaction workflow,
 * serving as basis for concrete platform transaction managers like
 * {@link org.springframework.transaction.jta.JtaTransactionManager}.
 *
 * <p>This base class provides the following workflow handling:
 * <ul>
 * <li>determines if there is an existing transaction;
 * <li>applies the appropriate propagation behavior;
 * <li>suspends and resumes transactions if necessary;
 * <li>checks the rollback-only flag on commit;
 * <li>applies the appropriate modification on rollback
 * (actual rollback or setting rollback-only);
 * <li>triggers registered synchronization callbacks
 * (if transaction synchronization is active).
 * </ul>
 *
 * <p>Subclasses have to implement specific template methods for specific
 * states of a transaction, e.g.: begin, suspend, resume, commit, rollback.
 * The most important of them are abstract and must be provided by a concrete
 * implementation; for the rest, defaults are provided, so overriding is optional.
 *
 * <p>Transaction synchronization is a generic mechanism for registering callbacks
 * that get invoked at transaction completion time. This is mainly used internally
 * by the data access support classes for JDBC, Hibernate, JPA, etc when running
 * within a JTA transaction: They register resources that are opened within the
 * transaction for closing at transaction completion time, allowing e.g. for reuse
 * of the same Hibernate Session within the transaction. The same mechanism can
 * also be leveraged for custom synchronization needs in an application.
 *
 * <p>The state of this class is serializable, to allow for serializing the
 * transaction strategy along with proxies that carry a transaction interceptor.
 * It is up to subclasses if they wish to make their state to be serializable too.
 * They should implement the {@code java.io.Serializable} marker interface in
 * that case, and potentially a private {@code readObject()} method (according
 * to Java serialization rules) if they need to restore any transient state.
 *
 * @author Juergen Hoeller
 * @see #setTransactionSynchronization
 * @see TransactionSynchronizationManager
 * @see org.springframework.transaction.jta.JtaTransactionManager
 * @since 28.03.2003
 */
@SuppressWarnings("serial")
public abstract class AbstractPlatformTransactionManager implements PlatformTransactionManager, Serializable {

	/**
	 * Always activate transaction synchronization, even for "empty" transactions
	 * that result from PROPAGATION_SUPPORTS with no existing backend transaction.
	 *
	 * @see org.springframework.transaction.TransactionDefinition#PROPAGATION_SUPPORTS
	 * @see org.springframework.transaction.TransactionDefinition#PROPAGATION_NOT_SUPPORTED
	 * @see org.springframework.transaction.TransactionDefinition#PROPAGATION_NEVER
	 */
	public static final int SYNCHRONIZATION_ALWAYS = 0;

	/**
	 * Activate transaction synchronization only for actual transactions,
	 * that is, not for empty ones that result from PROPAGATION_SUPPORTS with
	 * no existing backend transaction.
	 *
	 * @see org.springframework.transaction.TransactionDefinition#PROPAGATION_REQUIRED
	 * @see org.springframework.transaction.TransactionDefinition#PROPAGATION_MANDATORY
	 * @see org.springframework.transaction.TransactionDefinition#PROPAGATION_REQUIRES_NEW
	 */
	public static final int SYNCHRONIZATION_ON_ACTUAL_TRANSACTION = 1;

	/**
	 * Never active transaction synchronization, not even for actual transactions.
	 */
	public static final int SYNCHRONIZATION_NEVER = 2;


	/**
	 * Constants instance for AbstractPlatformTransactionManager.
	 */
	private static final Constants constants = new Constants(AbstractPlatformTransactionManager.class);


	protected transient Log logger = LogFactory.getLog(getClass());

	private int transactionSynchronization = SYNCHRONIZATION_ALWAYS;

	private int defaultTimeout = TransactionDefinition.TIMEOUT_DEFAULT;

	private boolean nestedTransactionAllowed = false;

	private boolean validateExistingTransaction = false;

	private boolean globalRollbackOnParticipationFailure = true;

	private boolean failEarlyOnGlobalRollbackOnly = false;

	private boolean rollbackOnCommitFailure = false;


	/**
	 * Set the transaction synchronization by the name of the corresponding constant
	 * in this class, e.g. "SYNCHRONIZATION_ALWAYS".
	 *
	 * @param constantName name of the constant
	 * @see #SYNCHRONIZATION_ALWAYS
	 */
	public final void setTransactionSynchronizationName(String constantName) {
		setTransactionSynchronization(constants.asNumber(constantName).intValue());
	}

	/**
	 * Set when this transaction manager should activate the thread-bound
	 * transaction synchronization support. Default is "always".
	 * <p>Note that transaction synchronization isn't supported for
	 * multiple concurrent transactions by different transaction managers.
	 * Only one transaction manager is allowed to activate it at any time.
	 *
	 * @see #SYNCHRONIZATION_ALWAYS
	 * @see #SYNCHRONIZATION_ON_ACTUAL_TRANSACTION
	 * @see #SYNCHRONIZATION_NEVER
	 * @see TransactionSynchronizationManager
	 * @see TransactionSynchronization
	 */
	public final void setTransactionSynchronization(int transactionSynchronization) {
		this.transactionSynchronization = transactionSynchronization;
	}

	/**
	 * Return if this transaction manager should activate the thread-bound
	 * transaction synchronization support.
	 */
	public final int getTransactionSynchronization() {
		return this.transactionSynchronization;
	}

	/**
	 * Specify the default timeout that this transaction manager should apply
	 * if there is no timeout specified at the transaction level, in seconds.
	 * <p>Default is the underlying transaction infrastructure's default timeout,
	 * e.g. typically 30 seconds in case of a JTA provider, indicated by the
	 * {@code TransactionDefinition.TIMEOUT_DEFAULT} value.
	 *
	 * @see org.springframework.transaction.TransactionDefinition#TIMEOUT_DEFAULT
	 */
	public final void setDefaultTimeout(int defaultTimeout) {
		if (defaultTimeout < TransactionDefinition.TIMEOUT_DEFAULT) {
			throw new InvalidTimeoutException("Invalid default timeout", defaultTimeout);
		}
		this.defaultTimeout = defaultTimeout;
	}

	/**
	 * Return the default timeout that this transaction manager should apply
	 * if there is no timeout specified at the transaction level, in seconds.
	 * <p>Returns {@code TransactionDefinition.TIMEOUT_DEFAULT} to indicate
	 * the underlying transaction infrastructure's default timeout.
	 */
	public final int getDefaultTimeout() {
		return this.defaultTimeout;
	}

	/**
	 * Set whether nested transactions are allowed. Default is "false".
	 * <p>Typically initialized with an appropriate default by the
	 * concrete transaction manager subclass.
	 */
	public final void setNestedTransactionAllowed(boolean nestedTransactionAllowed) {
		this.nestedTransactionAllowed = nestedTransactionAllowed;
	}

	/**
	 * Return whether nested transactions are allowed.
	 */
	public final boolean isNestedTransactionAllowed() {
		return this.nestedTransactionAllowed;
	}

	/**
	 * Set whether existing transactions should be validated before participating
	 * in them.
	 * <p>When participating in an existing transaction (e.g. with
	 * PROPAGATION_REQUIRED or PROPAGATION_SUPPORTS encountering an existing
	 * transaction), this outer transaction's characteristics will apply even
	 * to the inner transaction scope. Validation will detect incompatible
	 * isolation level and read-only settings on the inner transaction definition
	 * and reject participation accordingly through throwing a corresponding exception.
	 * <p>Default is "false", leniently ignoring inner transaction settings,
	 * simply overriding them with the outer transaction's characteristics.
	 * Switch this flag to "true" in order to enforce strict validation.
	 *
	 * @since 2.5.1
	 */
	public final void setValidateExistingTransaction(boolean validateExistingTransaction) {
		this.validateExistingTransaction = validateExistingTransaction;
	}

	/**
	 * Return whether existing transactions should be validated before participating
	 * in them.
	 *
	 * @since 2.5.1
	 */
	public final boolean isValidateExistingTransaction() {
		return this.validateExistingTransaction;
	}

	/**
	 * Set whether to globally mark an existing transaction as rollback-only
	 * after a participating transaction failed.
	 * <p>Default is "true": If a participating transaction (e.g. with
	 * PROPAGATION_REQUIRED or PROPAGATION_SUPPORTS encountering an existing
	 * transaction) fails, the transaction will be globally marked as rollback-only.
	 * The only possible outcome of such a transaction is a rollback: The
	 * transaction originator <i>cannot</i> make the transaction commit anymore.
	 * <p>Switch this to "false" to let the transaction originator make the rollback
	 * decision. If a participating transaction fails with an exception, the caller
	 * can still decide to continue with a different path within the transaction.
	 * However, note that this will only work as long as all participating resources
	 * are capable of continuing towards a transaction commit even after a data access
	 * failure: This is generally not the case for a Hibernate Session, for example;
	 * neither is it for a sequence of JDBC insert/update/delete operations.
	 * <p><b>Note:</b>This flag only applies to an explicit rollback attempt for a
	 * subtransaction, typically caused by an exception thrown by a data access operation
	 * (where TransactionInterceptor will trigger a {@code PlatformTransactionManager.rollback()}
	 * call according to a rollback rule). If the flag is off, the caller can handle the exception
	 * and decide on a rollback, independent of the rollback rules of the subtransaction.
	 * This flag does, however, <i>not</i> apply to explicit {@code setRollbackOnly}
	 * calls on a {@code TransactionStatus}, which will always cause an eventual
	 * global rollback (as it might not throw an exception after the rollback-only call).
	 * <p>The recommended solution for handling failure of a subtransaction
	 * is a "nested transaction", where the global transaction can be rolled
	 * back to a savepoint taken at the beginning of the subtransaction.
	 * PROPAGATION_NESTED provides exactly those semantics; however, it will
	 * only work when nested transaction support is available. This is the case
	 * with DataSourceTransactionManager, but not with JtaTransactionManager.
	 *
	 * @see #setNestedTransactionAllowed
	 * @see org.springframework.transaction.jta.JtaTransactionManager
	 */
	public final void setGlobalRollbackOnParticipationFailure(boolean globalRollbackOnParticipationFailure) {
		this.globalRollbackOnParticipationFailure = globalRollbackOnParticipationFailure;
	}

	/**
	 * Return whether to globally mark an existing transaction as rollback-only
	 * after a participating transaction failed.
	 */
	public final boolean isGlobalRollbackOnParticipationFailure() {
		return this.globalRollbackOnParticipationFailure;
	}

	/**
	 * Set whether to fail early in case of the transaction being globally marked
	 * as rollback-only.
	 * <p>Default is "false", only causing an UnexpectedRollbackException at the
	 * outermost transaction boundary. Switch this flag on to cause an
	 * UnexpectedRollbackException as early as the global rollback-only marker
	 * has been first detected, even from within an inner transaction boundary.
	 * <p>Note that, as of Spring 2.0, the fail-early behavior for global
	 * rollback-only markers has been unified: All transaction managers will by
	 * default only cause UnexpectedRollbackException at the outermost transaction
	 * boundary. This allows, for example, to continue unit tests even after an
	 * operation failed and the transaction will never be completed. All transaction
	 * managers will only fail earlier if this flag has explicitly been set to "true".
	 *
	 * @see org.springframework.transaction.UnexpectedRollbackException
	 * @since 2.0
	 */
	public final void setFailEarlyOnGlobalRollbackOnly(boolean failEarlyOnGlobalRollbackOnly) {
		this.failEarlyOnGlobalRollbackOnly = failEarlyOnGlobalRollbackOnly;
	}

	/**
	 * Return whether to fail early in case of the transaction being globally marked
	 * as rollback-only.
	 *
	 * @since 2.0
	 */
	public final boolean isFailEarlyOnGlobalRollbackOnly() {
		return this.failEarlyOnGlobalRollbackOnly;
	}

	/**
	 * Set whether {@code doRollback} should be performed on failure of the
	 * {@code doCommit} call. Typically not necessary and thus to be avoided,
	 * as it can potentially override the commit exception with a subsequent
	 * rollback exception.
	 * <p>Default is "false".
	 *
	 * @see #doCommit
	 * @see #doRollback
	 */
	public final void setRollbackOnCommitFailure(boolean rollbackOnCommitFailure) {
		this.rollbackOnCommitFailure = rollbackOnCommitFailure;
	}

	/**
	 * Return whether {@code doRollback} should be performed on failure of the
	 * {@code doCommit} call.
	 */
	public final boolean isRollbackOnCommitFailure() {
		return this.rollbackOnCommitFailure;
	}


	//---------------------------------------------------------------------
	// Implementation of PlatformTransactionManager
	//---------------------------------------------------------------------

	/**
	 * This implementation handles propagation behavior. Delegates to
	 * {@code doGetTransaction}, {@code isExistingTransaction}
	 * and {@code doBegin}.
	 * 此根据事务的传播行为做出不同的处理
	 *
	 * @see #doGetTransaction
	 * @see #isExistingTransaction
	 * @see #doBegin
	 */
	@Override
	public final TransactionStatus getTransaction(@Nullable TransactionDefinition definition)
			throws TransactionException {

		//如果未提供事务定义，那么使用一个默认事务定义对象StaticTransactionDefinition，这是单例模式的应用。
		//该默认事务定义对象具有默认的事务定义属性，默认值就是TransactionDefinition接口中定义的默认方法返回值
		TransactionDefinition def = (definition != null ? definition : TransactionDefinition.withDefaults());
		/*
		 * 获取当前事务，该方法由具体的事务管理器子类自己实现，比如DataSourceTransactionManager、JtaTransactionManager
		 * 一般都使用DataSourceTransactionManager这个事务管理器
		 *
		 * 对于DataSourceTransactionManager这里获取的实际上是一个数据库的事务连接对象，即DataSourceTransactionObject
		 */
		Object transaction = doGetTransaction();

		boolean debugEnabled = logger.isDebugEnabled();

		//isExistingTransaction，默认返回false，同样被具体的事务管理器子类重写
		//DataSourceTransactionManager的方法将会判断上面获取的DataSourceTransactionObject内部的数据库连接connectionHolder属性是否不为null，
		// 并且是否已经开启了事务。我们说过如果当前线程是第一次进来，那么connectionHolder就是null。
		if (isExistingTransaction(transaction)) {
			//如果已经存在事务，那么将检查传播行为并进行不同的处理，随后返回
			return handleExistingTransaction(def, transaction, debugEnabled);
		}

		/*
		 * 到这里表示没有已存在的事务，进入第一个事务方法时将会走这个逻辑
		 */

		//设置的事务超时时间如果小于默认超时时间（-1），将会抛出异常
		if (def.getTimeout() < TransactionDefinition.TIMEOUT_DEFAULT) {
			throw new InvalidTimeoutException("Invalid transaction timeout", def.getTimeout());
		}

		//如果配置的事务的传播行为是PROPAGATION_MANDATORY，该传播行为的含义是：
		//如果当前存在事务，则当前方法加入到该事务中去，如果当前不存在事务，则当前方法直接抛出异常。
		//这里就直接抛出异常。
		if (def.getPropagationBehavior() == TransactionDefinition.PROPAGATION_MANDATORY) {
			throw new IllegalTransactionStateException(
					"No existing transaction found for transaction marked with propagation 'mandatory'");
		}
		//否则，如果配置的事务的传播行为是PROPAGATION_REQUIRED或者PROPAGATION_REQUIRES_NEW或者PROPAGATION_NESTED，
		//这几个传播行为的含义的共同点之一就是：如果当前不存在事务，就创建一个新事务运行。
		//那么这里将开启一个新事物。
		else if (def.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRED ||
				def.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW ||
				def.getPropagationBehavior() == TransactionDefinition.PROPAGATION_NESTED) {
			//暂停给定的事务。首先挂起事务同步，然后再委派给doSuspend模板方法。
			//由于此前没有事务，所以参数事务为null
			SuspendedResourcesHolder suspendedResources = suspend(null);
			if (debugEnabled) {
				logger.debug("Creating new transaction with name [" + def.getName() + "]: " + def);
			}
			try {
				//开启一个新事务并返回
				return startTransaction(def, transaction, debugEnabled, suspendedResources);
			} catch (RuntimeException | Error ex) {
				//唤醒此前挂起的事务和资源
				resume(null, suspendedResources);
				throw ex;
			}
		}
		//否则，配置的事务的传播行为就是剩下的三种：PROPAGATION_SUPPORTS或PROPAGATION_NEVER或PROPAGATION_NOT_SUPPORTED，
		//这几个传播行为的含义的共同点之一就是：当前方法一定以非事务的方式运行。
		else {
			/*将会创建“空”事务：即没有实际的事务，但是有潜在的同步性。*/
			//如果配置的事务的隔离级别不是是u巨酷默认的隔离级别，那么输出警告：
			//虽然指定了自定义的隔离级别，但是由于未启动任何事物，那么隔离级别也就不会生效了
			if (def.getIsolationLevel() != TransactionDefinition.ISOLATION_DEFAULT && logger.isWarnEnabled()) {
				logger.warn("Custom isolation level specified but no actual transaction initiated; " +
						"isolation level will effectively be ignored: " + def);
			}
			boolean newSynchronization = (getTransactionSynchronization() == SYNCHRONIZATION_ALWAYS);
			//为给定参数创建一个新的TransactionStatus，并在适当时初始化事务同步，但是不会真正开启事务。
			//和startTransaction相比，其内部会调用newTransactionStatus和prepareSynchronization，但不会调用doBegin方法
			return prepareTransactionStatus(def, null, true, newSynchronization, debugEnabled, null);
		}
	}

	/**
	 * Start a new transaction.
	 * 开启一个新事物
	 *
	 * @param definition         为当前方法配置的事务定义
	 * @param transaction        获取的事务对象，对于DataSourceTransactionManager来说就是DataSourceTransactionObject
	 * @param debugEnabled       日志级别
	 * @param suspendedResources 被挂起的资源
	 * @return 新开启的事务TransactionStatus
	 */
	private TransactionStatus startTransaction(TransactionDefinition definition, Object transaction,
											   boolean debugEnabled, @Nullable SuspendedResourcesHolder suspendedResources) {
		//判断是否需要开启新同步，默认都是SYNCHRONIZATION_ALWAYS，即需要开启
		boolean newSynchronization = (getTransactionSynchronization() != SYNCHRONIZATION_NEVER);
		//新建一个TransactionStatus对象，内部持有transaction对象
		DefaultTransactionStatus status = newTransactionStatus(
				definition, transaction, true, newSynchronization, debugEnabled, suspendedResources);
		/*
		 * 真正的开启事务，该方法由具体的子类自己实现
		 */
		doBegin(transaction, definition);
		//准备事务同步
		prepareSynchronization(status, definition);
		return status;
	}

	/**
	 * Create a TransactionStatus for an existing transaction.
	 * 根据现有事务创建一个TransactionStatus，处理事务的传播行为
	 *
	 * @param definition   当前事务定义
	 * @param transaction  事物对象，内部包含此前的事务信息
	 * @param debugEnabled 日志级别支持
	 * @return 事务状态对象
	 */
	private TransactionStatus handleExistingTransaction(
			TransactionDefinition definition, Object transaction, boolean debugEnabled)
			throws TransactionException {
		/*
		 * 1 如果当前配置的传播行为是PROPAGATION_NEVER，该行为的特点是：
		 * 当前方法一定以非事务的方式运行，并且如果当前存在事务，则直接抛出异常
		 * 所以这里直接抛出异常
		 */
		if (definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_NEVER) {
			throw new IllegalTransactionStateException(
					"Existing transaction found for transaction marked with propagation 'never'");
		}
		/*
		 * 2 如果当前配置的传播行为是PROPAGATION_NOT_SUPPORTED，该行为的特点是：
		 * 当前方法一定以非事务的方式运行，如果当前存在事务，则把当前事务挂起，直到当前方法执行完毕，才恢复外层事务。
		 */
		if (definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_NOT_SUPPORTED) {
			if (debugEnabled) {
				logger.debug("Suspending current transaction");
			}
			//那么这里挂起外层事务
			Object suspendedResources = suspend(transaction);
			//判断是否需要进行新同步，默认都是需要的
			boolean newSynchronization = (getTransactionSynchronization() == SYNCHRONIZATION_ALWAYS);
			//为给定参数创建一个新的TransactionStatus，并在适当时初始化事务同步。
			//这里的第二个参数事务属性为null，表示当前方法以非事务的方式执行
			return prepareTransactionStatus(
					definition, null, false, newSynchronization, debugEnabled, suspendedResources);
		}
		/*
		 * 3 如果当前配置的传播行为是PROPAGATION_REQUIRES_NEW，该行为的特点是：
		 * 当前方法开启一个新事物独立运行，从不参与外部的现有事务。则当内部事务开始执行时，
		 * 外部事务（如果存在）将被挂起，内务事务结束时，外部事务将继续执行。
		 */
		if (definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW) {
			if (debugEnabled) {
				logger.debug("Suspending current transaction, creating new transaction with name [" +
						definition.getName() + "]");
			}
			//那么这里挂起外层事务，返回被挂起的资源
			SuspendedResourcesHolder suspendedResources = suspend(transaction);
			try {
				//开启一个新事务并返回
				return startTransaction(definition, transaction, debugEnabled, suspendedResources);
			} catch (RuntimeException | Error beginEx) {
				//恢复被挂起的事务
				resumeAfterBeginException(transaction, suspendedResources, beginEx);
				throw beginEx;
			}
		}
		/*
		 * 4 如果当前配置的传播行为是PROPAGATION_NESTED，该行为的特点是：
		 * 如果当前存在事务，则创建一个新“事务”作为当前事务的嵌套事务来运行；
		 * 如果当前没有事务，则等价于PROPAGATION_REQUIRED，即会新建一个事务运行。
		 *
		 */
		if (definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_NESTED) {
			//判断是否允许PROPAGATION_NESTED行为，默认不允许，但是DataSourceTransactionManager重写为为允许
			if (!isNestedTransactionAllowed()) {
				throw new NestedTransactionNotSupportedException(
						"Transaction manager does not allow nested transactions by default - " +
								"specify 'nestedTransactionAllowed' property with value 'true'");
			}
			if (debugEnabled) {
				logger.debug("Creating nested transaction with name [" + definition.getName() + "]");
			}
			//返回是否对嵌套事务使用保存点，默认true，JtaTransactionManager设置为false
			//PROPAGATION_NESTED就是通过Savepoint保存点来实现的
			if (useSavepointForNestedTransaction()) {
				//并没有挂起当前事务，创建TransactionStatus，transaction参数就是当前事务
				DefaultTransactionStatus status =
						prepareTransactionStatus(definition, transaction, false, false, debugEnabled, null);
				/*
				 * 通过TransactionStatus实现的SavepointManager API在现有的Spring管理的事务中创建保存点，通常使用JDBC 3.0保存点。
				 */
				status.createAndHoldSavepoint();
				return status;
			} else {
				// 通过在事务中嵌套的begin和commit / rollback调用开启的嵌套事务。
				// 通常仅用于JTA：如果存在预先存在的JTA事务，则可以在此处激活Spring同步。
				return startTransaction(definition, transaction, debugEnabled, null);
			}
		}
		//剩下的传播行为就是PROPAGATION_SUPPORTS或者PROPAGATION_REQUIRED或者PROPAGATION_MANDATORY。

		if (debugEnabled) {
			logger.debug("Participating in existing transaction");
		}
		//是否在参与现有事务之前进行验证，默认false
		if (isValidateExistingTransaction()) {
			if (definition.getIsolationLevel() != TransactionDefinition.ISOLATION_DEFAULT) {
				Integer currentIsolationLevel = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
				if (currentIsolationLevel == null || currentIsolationLevel != definition.getIsolationLevel()) {
					Constants isoConstants = DefaultTransactionDefinition.constants;
					throw new IllegalTransactionStateException("Participating transaction with definition [" +
							definition + "] specifies isolation level which is incompatible with existing transaction: " +
							(currentIsolationLevel != null ?
									isoConstants.toCode(currentIsolationLevel, DefaultTransactionDefinition.PREFIX_ISOLATION) :
									"(unknown)"));
				}
			}
			if (!definition.isReadOnly()) {
				if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
					throw new IllegalTransactionStateException("Participating transaction with definition [" +
							definition + "] is not marked as read-only but existing transaction is");
				}
			}
		}
		//并没有挂起当前事务，而是直接参与到当前事务中去，transaction参数就是当前的事务
		boolean newSynchronization = (getTransactionSynchronization() != SYNCHRONIZATION_NEVER);
		return prepareTransactionStatus(definition, transaction, false, newSynchronization, debugEnabled, null);
	}

	/**
	 * Create a new TransactionStatus for the given arguments,
	 * also initializing transaction synchronization as appropriate.
	 * 根据给定参数创建一个新的TransactionStatus，并在适当时初始化事务同步，不会真正开启新事物。
	 *
	 * @param definition         为当前方法设置的事务定义
	 * @param transaction        当前已存在的事务
	 * @param newTransaction     是否是新事物，如果是外层事务方法，则为true，如果是内层方法则为false
	 * @param newSynchronization 是否是新事务同步
	 * @param debug              是否支持debug日志
	 * @param suspendedResources 被挂起的资源
	 * @return 一个TransactionStatus的实现
	 */
	protected final DefaultTransactionStatus prepareTransactionStatus(
			TransactionDefinition definition, @Nullable Object transaction, boolean newTransaction,
			boolean newSynchronization, boolean debug, @Nullable Object suspendedResources) {
		//通过newTransactionStatus创建一个DefaultTransactionStatus
		DefaultTransactionStatus status = newTransactionStatus(
				definition, transaction, newTransaction, newSynchronization, debug, suspendedResources);

		//中间缺少了doBegin真正开启事务的访法，所以仅仅是创建了一个简单的TransactionStatus
		//包存了一些其他信息，比如被挂起的资源信息

		//准备事务同步
		prepareSynchronization(status, definition);
		return status;
	}

	/**
	 * Create a TransactionStatus instance for the given arguments.
	 * 为给定参数新创建一个TransactionStatus实例，实际类型为DefaultTransactionStatus
	 *
	 * @param definition         为当前方法配置的事务定义
	 * @param transaction        获取的事务对象
	 * @param newTransaction     是否是新事物
	 * @param newSynchronization 是否开启事务同步
	 * @param debug              是否支持debug级别的日志
	 * @param suspendedResources 被挂起的资源，比如此前的事务同步
	 * @return DefaultTransactionStatus对象
	 */
	protected DefaultTransactionStatus newTransactionStatus(
			TransactionDefinition definition, @Nullable Object transaction, boolean newTransaction,
			boolean newSynchronization, boolean debug, @Nullable Object suspendedResources) {
		//如果newSynchronization为true并且当前线程没有绑定的事务同步，那么确定开启新事物同步
		//由于此前调用了suspend方法清理了此前的事务同步，因此一般都是需要开启新事务同步，即为true
		boolean actualNewSynchronization = newSynchronization &&
				!TransactionSynchronizationManager.isSynchronizationActive();
		//返回一个新建的DefaultTransactionStatus对象，该对象被用来表示新开启的事务，是TransactionStatus的默认实现
		//内部包括了各种新开启的事务状态，当然包括此前挂起的事务的资源
		return new DefaultTransactionStatus(
				transaction, newTransaction, actualNewSynchronization,
				definition.isReadOnly(), debug, suspendedResources);
	}

	/**
	 * Initialize transaction synchronization as appropriate.
	 * 适当地初始化事务同步。
	 */
	protected void prepareSynchronization(DefaultTransactionStatus status, TransactionDefinition definition) {
		//是否是新同步，在真正的开启新事务的时候（比如第一次进入事务方法或者传播行为是REQUIRES_NEW），一般都是true
		if (status.isNewSynchronization()) {
			//配置当前事务的一系列属性
			//是否具有事务
			TransactionSynchronizationManager.setActualTransactionActive(status.hasTransaction());
			//传播行为
			TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(
					definition.getIsolationLevel() != TransactionDefinition.ISOLATION_DEFAULT ?
							definition.getIsolationLevel() : null);
			//只读状态
			TransactionSynchronizationManager.setCurrentTransactionReadOnly(definition.isReadOnly());
			//事务名
			TransactionSynchronizationManager.setCurrentTransactionName(definition.getName());
			//初始化同步
			TransactionSynchronizationManager.initSynchronization();
		}
	}

	/**
	 * Determine the actual timeout to use for the given definition.
	 * Will fall back to this manager's default timeout if the
	 * transaction definition doesn't specify a non-default value.
	 * 确定给定事务定义的实际超时时间。
	 * 如果事务定义未指定非默认值，则将使用默认超时。
	 *
	 * @param definition the transaction definition
	 * @return the actual timeout to use
	 * @see org.springframework.transaction.TransactionDefinition#getTimeout()
	 * @see #setDefaultTimeout
	 */
	protected int determineTimeout(TransactionDefinition definition) {
		//如果不是默认超时时间，那么使用指定的事件
		if (definition.getTimeout() != TransactionDefinition.TIMEOUT_DEFAULT) {
			return definition.getTimeout();
		}
		//否则使用默认超时
		return getDefaultTimeout();
	}


	/**
	 * Suspend the given transaction. Suspends transaction synchronization first,
	 * then delegates to the {@code doSuspend} template method.
	 *
	 * @param transaction the current transaction object
	 *                    (or {@code null} to just suspend active synchronizations, if any)
	 * @return an object that holds suspended resources
	 * (or {@code null} if neither transaction nor synchronization active)
	 * 挂起给定的事务。首先挂起当前线程的事务同步回调，然后再委派给doSuspend模板方法由子类来实现挂起当前事务。
	 * @see #doSuspend
	 * @see #resume
	 */
	@Nullable
	protected final SuspendedResourcesHolder suspend(@Nullable Object transaction) throws TransactionException {
		//如果当前线程的事务同步处于活动状态，即存在绑定的TransactionSynchronization，则返回true。
		//如果是第一次因为进来，那么自然为false
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			//挂起当前线程的所有事务同步回调，这类似于@TransactionalEventListener，并返回"被挂起"的回调
			List<TransactionSynchronization> suspendedSynchronizations = doSuspendSynchronization();
			try {
				Object suspendedResources = null;
				if (transaction != null) {
					//挂起事务，由具体的子类实现
					suspendedResources = doSuspend(transaction);
				}
				/*获取当前事务的信息，并且清空各个ThreadLocal缓存中的当前线程的当前事务信息（恢复为默认值）*/

				//获取并清空（设置为null）事物名称
				String name = TransactionSynchronizationManager.getCurrentTransactionName();
				TransactionSynchronizationManager.setCurrentTransactionName(null);
				//获取并清空（设置为false）事物只读状态
				boolean readOnly = TransactionSynchronizationManager.isCurrentTransactionReadOnly();
				TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
				//获取并清空（设置为null）事物隔离级别
				Integer isolationLevel = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
				TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(null);
				//获取并清空（设置为false）事物是否激活
				boolean wasActive = TransactionSynchronizationManager.isActualTransactionActive();
				TransactionSynchronizationManager.setActualTransactionActive(false);
				//将获取的当前事物的信息存入一个SuspendedResourcesHolder对象中返回
				return new SuspendedResourcesHolder(
						suspendedResources, suspendedSynchronizations, name, readOnly, isolationLevel, wasActive);
			} catch (RuntimeException | Error ex) {
				// doSuspend failed - original transaction is still active...
				doResumeSynchronization(suspendedSynchronizations);
				throw ex;
			}
		}
		//如果没有事务同步但是开启了事务，那么挂起事务
		else if (transaction != null) {
			//挂起事务，由具体的子类实现
			Object suspendedResources = doSuspend(transaction);
			//将挂起的资源存入一个SuspendedResourcesHolder对象中返回
			return new SuspendedResourcesHolder(suspendedResources);
		} else {
			//事务或者事务同步均未激活，返回null，什么也不干
			return null;
		}
	}

	/**
	 * Resume the given transaction. Delegates to the {@code doResume}
	 * template method first, then resuming transaction synchronization.
	 * 恢复给定的事务。首先委托doResume模板方法，然后恢复事务同步。
	 *
	 * @param transaction     the current transaction object 当前事务对象
	 * @param resourcesHolder the object that holds suspended resources,
	 *                        as returned by {@code suspend} (or {@code null} to just
	 *                        resume synchronizations, if any) 此前被挂起的资源
	 * @see #doResume
	 * @see #suspend
	 */
	protected final void resume(@Nullable Object transaction, @Nullable SuspendedResourcesHolder resourcesHolder)
			throws TransactionException {
		if (resourcesHolder != null) {
			//被挂起的事务资源
			Object suspendedResources = resourcesHolder.suspendedResources;
			if (suspendedResources != null) {
				//首先调用doResume恢复当前事务的资源。事务同步将在此后恢复。
				//该方法作为模版方法，由子类来实现
				doResume(transaction, suspendedResources);
			}
			//被挂起的事物同步
			List<TransactionSynchronization> suspendedSynchronizations = resourcesHolder.suspendedSynchronizations;
			//如果存在此前被挂起的事务同步
			if (suspendedSynchronizations != null) {
				//那么这里恢复绑定此前的各种事务属性
				TransactionSynchronizationManager.setActualTransactionActive(resourcesHolder.wasActive);
				TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(resourcesHolder.isolationLevel);
				TransactionSynchronizationManager.setCurrentTransactionReadOnly(resourcesHolder.readOnly);
				TransactionSynchronizationManager.setCurrentTransactionName(resourcesHolder.name);
				///唤醒事务同步
				doResumeSynchronization(suspendedSynchronizations);
			}
		}
	}

	/**
	 * Resume outer transaction after inner transaction begin failed.
	 */
	private void resumeAfterBeginException(
			Object transaction, @Nullable SuspendedResourcesHolder suspendedResources, Throwable beginEx) {

		try {
			resume(transaction, suspendedResources);
		} catch (RuntimeException | Error resumeEx) {
			String exMessage = "Inner transaction begin exception overridden by outer transaction resume exception";
			logger.error(exMessage, beginEx);
			throw resumeEx;
		}
	}

	/**
	 * Suspend all current synchronizations and deactivate transaction
	 * synchronization for the current thread.
	 * 挂起所有当前同步，并停用当前线程的事务同步。
	 *
	 * @return the List of suspended TransactionSynchronization objects
	 * 挂起的TransactionSynchronization对象的列表
	 */
	private List<TransactionSynchronization> doSuspendSynchronization() {
		//获取线程的当前的所有事务同步列表
		List<TransactionSynchronization> suspendedSynchronizations =
				TransactionSynchronizationManager.getSynchronizations();
		//遍历，依次挂起每一个事务同步
		for (TransactionSynchronization synchronization : suspendedSynchronizations) {
			synchronization.suspend();
		}
		//清除synchronizations属性中保存的的当前线程的当前事务同步集合的引用
		TransactionSynchronizationManager.clearSynchronization();
		//返回被挂起的事务同步
		return suspendedSynchronizations;
	}


	/**
	 * Reactivate transaction synchronization for the current thread
	 * and resume all given synchronizations.
	 *
	 * @param suspendedSynchronizations a List of TransactionSynchronization objects
	 */
	private void doResumeSynchronization(List<TransactionSynchronization> suspendedSynchronizations) {
		TransactionSynchronizationManager.initSynchronization();
		for (TransactionSynchronization synchronization : suspendedSynchronizations) {
			synchronization.resume();
			TransactionSynchronizationManager.registerSynchronization(synchronization);
		}
	}


	/**
	 * This implementation of commit handles participating in existing
	 * transactions and programmatic rollback requests.
	 * 尝试提交事务，但仍可能会回滚
	 * Delegates to {@code isRollbackOnly}, {@code doCommit}
	 * and {@code rollback}.
	 * 内部委托给isRollbackOnly，doCommit和rollback方法
	 *
	 * @see org.springframework.transaction.TransactionStatus#isRollbackOnly()
	 * @see #doCommit
	 * @see #rollback
	 */
	@Override
	public final void commit(TransactionStatus status) throws TransactionException {
		//如果事务已完成，则抛出异常
		if (status.isCompleted()) {
			throw new IllegalTransactionStateException(
					"Transaction is already completed - do not call commit or rollback more than once per transaction");
		}
		//获取当前事务状态
		DefaultTransactionStatus defStatus = (DefaultTransactionStatus) status;
		/*
		 * 1 如果事务明确被设置为仅回滚，那么执行回滚
		 */
		if (defStatus.isLocalRollbackOnly()) {
			if (defStatus.isDebug()) {
				logger.debug("Transactional code has requested rollback");
			}
			//处理回滚，该方法此前就见过了
			processRollback(defStatus, false);
			return;
		}
		/*
		 * shouldCommitOnGlobalRollbackOnly方法用于判断返回是否以全局方式对已标记为仅回滚的事务调用doCommit提交，
		 * 默认实现返回false，即不会提交，而是一起回滚，但是JtaTransactionManager重写返回true
		 * 并且如果当前事务被设置为全局回滚，对于DataSourceTransactionObject来说就是判断内部的ConnectionHolder的rollbackOnly属性
		 *
		 * 2 以上条件都返回true，那么表示会执行回滚
		 */
		if (!shouldCommitOnGlobalRollbackOnly() && defStatus.isGlobalRollbackOnly()) {
			if (defStatus.isDebug()) {
				logger.debug("Global transaction is marked as rollback-only but transactional code requested commit");
			}
			/*
			 * 处理回滚，该方法此前就见过了。注意这里的unexpected参数为true
			 * 对于加入到外层事务的行为，如果在内层方法中进行了回滚，即使异常被捕获，由于被设置为了仅回滚，那么该事物的所有操作仍然会回滚
			 * 并且还会在处理最外层事务方法时抛出UnexpectedRollbackException异常，来提醒开发者所有的外部和内部操作都已被回滚！
			 *
			 * 这一般对于内层PROPAGATION_SUPPORTS或者内层PROPAGATION_REQUIRED或者内层PROPAGATION_MANDATORY生效，
			 * 对于内层PROPAGATION_NOT_SUPPORTED则无效。
			 */
			processRollback(defStatus, true);
			return;
		}
		/*
		 * 3 最后才会真正的提交
		 */
		processCommit(defStatus);
	}

	/**
	 * Process an actual commit.
	 * Rollback-only flags have already been checked and applied.
	 *处理实际的提交。Rollback-only标志已被检查和应用。
	 * @param status object representing the transaction 代表当前事务对象
	 * @throws TransactionException in case of commit failure
	 */
	private void processCommit(DefaultTransactionStatus status) throws TransactionException {
		try {
			//beforeCompletion方法已回调的标志位
			boolean beforeCompletionInvoked = false;

			try {
				//意外的回滚标志
				boolean unexpectedRollback = false;
				/*
				 * 1 准备提交，要在beforeCommit同步回调发生之前执行
				 * 该方法为空实现，留给子类重写
				 */
				prepareForCommit(status);
				/*
				 * 2 触发beforeCommit回调。
				 * 在当前线程所有当前已注册的TransactionSynchronization上触发beforeCommit方法回调
				 */
				triggerBeforeCommit(status);
				/*
				 * 3 触发beforeCompletion回调。
				 * 在当前线程所有当前已注册的TransactionSynchronization上触发beforeCompletion方法回调
				 */
				triggerBeforeCompletion(status);
				//标志位改为true
				beforeCompletionInvoked = true;

				/*
				 * 2 判断是否具有保存点，内层PROPAGATION_NESTED事务会开启保存点，即嵌套事务
				 */
				if (status.hasSavepoint()) {
					if (status.isDebug()) {
						logger.debug("Releasing transaction savepoint");
					}
					unexpectedRollback = status.isGlobalRollbackOnly();
					/*
					 * 如果具有保存点，因为保存点内部的代码正常执行完毕，那么就释放保存点
					 * 但是并不会提交事务，而是需要等待外层事务方法去提交
					 */
					status.releaseHeldSavepoint();
				}
				/*
				 * 3 判断是否是新开的事务或者是最外层事务，比如外层PROPAGATION_REQUIRED
				 * 外层PROPAGATION_REQUIRED以及PROPAGATION_REQUIRES_NEW
				 */
				else if (status.isNewTransaction()) {
					if (status.isDebug()) {
						logger.debug("Initiating transaction commit");
					}
					unexpectedRollback = status.isGlobalRollbackOnly();
					/*
					 * 如果是新开启的事务或者最外层事务，那么就提交该事物
					 * 该方法由具体的事务管理器子类来实现，真正的实现事务的提交
					 */
					doCommit(status);
				}
				// 返回在事务被全局标记为"仅回滚"的情况下是否尽早失败，即是否需要立即抛出异常
				//一般为false，因此结果就是true，导致unexpectedRollback为false
				else if (isFailEarlyOnGlobalRollbackOnly()) {
					unexpectedRollback = status.isGlobalRollbackOnly();
				}

				//如果我们有一个全局rollback-only的标记，但仍未从提交中获得相应的异常，
				//则抛出UnexpectedRollbackException,但是此时事务已经被提交了
				if (unexpectedRollback) {
					throw new UnexpectedRollbackException(
							"Transaction silently rolled back because it has been marked as rollback-only");
				}
			}
			/*处理各种异常*/ catch (UnexpectedRollbackException ex) {
				// can only be caused by doCommit
				//如果在执行以上方法过程中抛出UnexpectedRollbackException异常
				//那么在当前线程所有当前已注册的TransactionSynchronization上触发afterCompletion方法回调
				triggerAfterCompletion(status, TransactionSynchronization.STATUS_ROLLED_BACK);
				throw ex;
			} catch (TransactionException ex) {
				//如果在执行以上方法过程中抛出TransactionException异常


				//返回是否在doCommit调用失败时执行doRollback，一般为false，即不会
				if (isRollbackOnCommitFailure()) {
					doRollbackOnCommitException(status, ex);
				} else {
					//在当前线程所有当前已注册的TransactionSynchronization上触发afterCompletion方法回调
					triggerAfterCompletion(status, TransactionSynchronization.STATUS_UNKNOWN);
				}
				throw ex;
			} catch (RuntimeException | Error ex) {
				//如果在执行beforeCompletion回调、回滚保存点、回滚事务等过程中抛出RuntimeException或者Error异常
				//那么在当前线程所有当前已注册的TransactionSynchronization上触发afterCompletion方法回调

				//如果还没有触发beforeCompletion方法回调
				if (!beforeCompletionInvoked) {
					//触发beforeCompletion回调。
					triggerBeforeCompletion(status);
				}
				//调用doRollback方法处理提交事务时的异常，随后触发afterCompletion方法回调
				doRollbackOnCommitException(status, ex);
				throw ex;
			}

			/*
			 * 4 事务成功提交后调用，触发afterCommit回调。
			 * 在当前线程所有当前已注册的TransactionSynchronization上触发afterCommit方法回调
			 */
			try {
				triggerAfterCommit(status);
			} finally {
				//最终触发afterCompletion方法回调
				triggerAfterCompletion(status, TransactionSynchronization.STATUS_COMMITTED);
			}

		} finally {
			/*
			 * 6 完成后进行清理，必要时清除同步，然后调用doCleanupAfterCompletion。
			 */
			cleanupAfterCompletion(status);
		}
	}

	/**
	 * This implementation of rollback handles participating in existing
	 * transactions. Delegates to {@code doRollback} and
	 * {@code doSetRollbackOnly}.
	 * 回滚事务。核心方法是doRollback和doSetRollbackOnly。
	 *
	 * @see #doRollback
	 * @see #doSetRollbackOnly
	 */
	@Override
	public final void rollback(TransactionStatus status) throws TransactionException {
		//如果当前事务已完成，即已提交或者回滚，那么抛出异常
		if (status.isCompleted()) {
			throw new IllegalTransactionStateException(
					"Transaction is already completed - do not call commit or rollback more than once per transaction");
		}

		DefaultTransactionStatus defStatus = (DefaultTransactionStatus) status;
		//处理回滚
		processRollback(defStatus, false);
	}

	/**
	 * Process an actual rollback.
	 * The completed flag has already been checked.
	 * 处理实际的回滚。
	 *
	 * @param status object representing the transaction 事务状态，代表着当前事务
	 * @throws TransactionException in case of rollback failure 如果回滚失败
	 */
	private void processRollback(DefaultTransactionStatus status, boolean unexpected) {
		try {
			boolean unexpectedRollback = unexpected;

			try {
				/*
				 * 1 在当前线程所有当前已注册的TransactionSynchronization上触发beforeCompletion方法回调
				 */
				triggerBeforeCompletion(status);
				/*
				 * 2 判断是否具有保存点，内层PROPAGATION_NESTED事务会开启保存点
				 */
				if (status.hasSavepoint()) {
					if (status.isDebug()) {
						logger.debug("Rolling back transaction to savepoint");
					}
					/*
					 * 如果具有保存点，那么就仅仅回滚保存点
					 * 但是我们在invokeWithinTransaction方法中知道，completeTransactionAfterThrowing
					 * 方法执行完毕仍然会抛出异常到外层事务中，因此仍然可能导致外层事务的回滚
					 */
					status.rollbackToHeldSavepoint();
				}
				/*
				 * 3 判断是否是新开的事务或者是最外层事务，比如外层PROPAGATION_REQUIRED
				 * 外层PROPAGATION_REQUIRED以及PROPAGATION_REQUIRES_NEW
				 */
				else if (status.isNewTransaction()) {
					if (status.isDebug()) {
						logger.debug("Initiating transaction rollback");
					}
					/*
					 * 如果是新开启的事务，那么就回滚该事物
					 * 该方法由具体的事务管理器子类来实现
					 */
					doRollback(status);
				}
				/*
				 * 4 到这里，表示没有保存点，并且也不是最外层事务，而是当前事务方法参数到了外层事务中
				 * 比如内层PROPAGATION_REQUIRED、内层PROPAGATION_SUPPORTS、内层PROPAGATION_MANDATORY
				 */
				else {
					// Participating in larger transaction
					// 如果status具有事务，那么这里表示外层的事务，这里就参与到外层事务的回滚操作中
					if (status.hasTransaction()) {
						/*
						 * 如果当前事务被设置为仅回滚，或者当前事务管理器的globalRollbackOnParticipationFailure属性为true，那么将事务设置为仅回滚
						 *
						 * globalRollbackOnParticipationFailure属性默认为true，表示只要你的参与事务失败了，就标记此事务为rollback-only
						 * 表示它只能给与回滚  而不能再commit或者正常结束了，也就是说，如果参与事务回滚，那么外部事物一定会回滚，即使内部的异常被catch了
						 */
						if (status.isLocalRollbackOnly() || isGlobalRollbackOnParticipationFailure()) {
							if (status.isDebug()) {
								logger.debug("Participating transaction failed - marking existing transaction as rollback-only");
							}
							/*
							 * 将事务设置为仅回滚，即外层事务也将会回滚
							 * 该方法由具体的事务管理器子类来实现
							 */
							doSetRollbackOnly(status);
						} else {
							if (status.isDebug()) {
								logger.debug("Participating transaction failed - letting transaction originator decide on rollback");
							}
						}
					} else {
						logger.debug("Should roll back transaction but cannot - no transaction available");
					}
					// Unexpected rollback only matters here if we're asked to fail early
					// 返回在事务被全局标记为"仅回滚"的情况下是否尽早失败，即是否需要立即抛出异常
					//一般为false，因此结果就是true，导致unexpectedRollback为false
					if (!isFailEarlyOnGlobalRollbackOnly()) {
						unexpectedRollback = false;
					}
				}
			} catch (RuntimeException | Error ex) {
				//即使在执行beforeCompletion回调、回滚保存点、回滚事务等过程中抛出RuntimeException或者Error异常
				//仍然会在当前线程所有当前已注册的TransactionSynchronization上触发afterCompletion方法回调
				triggerAfterCompletion(status, TransactionSynchronization.STATUS_UNKNOWN);
				throw ex;
			}

			//即使在执行beforeCompletion回调、回滚保存点、回滚事务等过程中抛出RuntimeException或者Error异常
			//仍然会在当前线程所有当前已注册的TransactionSynchronization上触发afterCompletion方法回调
			triggerAfterCompletion(status, TransactionSynchronization.STATUS_ROLLED_BACK);

			// Raise UnexpectedRollbackException if we had a global rollback-only marker
			//如果我们有一个仅全局回滚的标记，则引发UnexpectedRollbackException异常,一般来说不会抛出
			if (unexpectedRollback) {
				throw new UnexpectedRollbackException(
						"Transaction rolled back because it has been marked as rollback-only");
			}
		} finally {
			/*
			 * 6 完成后进行清理，必要时清除同步，然后调用doCleanupAfterCompletion。
			 */
			cleanupAfterCompletion(status);
		}
	}

	/**
	 * Invoke {@code doRollback}, handling rollback exceptions properly.
	 *
	 * @param status object representing the transaction
	 * @param ex     the thrown application exception or error
	 * @throws TransactionException in case of rollback failure
	 * @see #doRollback
	 */
	private void doRollbackOnCommitException(DefaultTransactionStatus status, Throwable ex) throws TransactionException {
		try {
			if (status.isNewTransaction()) {
				if (status.isDebug()) {
					logger.debug("Initiating transaction rollback after commit exception", ex);
				}
				doRollback(status);
			} else if (status.hasTransaction() && isGlobalRollbackOnParticipationFailure()) {
				if (status.isDebug()) {
					logger.debug("Marking existing transaction as rollback-only after commit exception", ex);
				}
				doSetRollbackOnly(status);
			}
		} catch (RuntimeException | Error rbex) {
			logger.error("Commit exception overridden by rollback exception", ex);
			triggerAfterCompletion(status, TransactionSynchronization.STATUS_UNKNOWN);
			throw rbex;
		}
		triggerAfterCompletion(status, TransactionSynchronization.STATUS_ROLLED_BACK);
	}


	/**
	 * Trigger {@code beforeCommit} callbacks.
	 *
	 * @param status object representing the transaction
	 */
	protected final void triggerBeforeCommit(DefaultTransactionStatus status) {
		if (status.isNewSynchronization()) {
			TransactionSynchronizationUtils.triggerBeforeCommit(status.isReadOnly());
		}
	}

	/**
	 * Trigger {@code beforeCompletion} callbacks.
	 *
	 * @param status object representing the transaction
	 */
	protected final void triggerBeforeCompletion(DefaultTransactionStatus status) {
		//如果存在事务同步
		if (status.isNewSynchronization()) {
			//在所有当前已注册的TransactionSynchronization上触发beforeCompletion回调
			TransactionSynchronizationUtils.triggerBeforeCompletion();
		}
	}

	/**
	 * Trigger {@code afterCommit} callbacks.
	 *
	 * @param status object representing the transaction
	 */
	private void triggerAfterCommit(DefaultTransactionStatus status) {
		if (status.isNewSynchronization()) {
			TransactionSynchronizationUtils.triggerAfterCommit();
		}
	}

	/**
	 * Trigger {@code afterCompletion} callbacks.
	 * 触发afterCompletion回调。
	 *
	 * @param status           object representing the transaction
	 * 事务状态，代表着当前事务
	 * @param completionStatus completion status according to TransactionSynchronization constants
	 * TransactionSynchronization常量表示的事务完成状态，如果回滚处理成功则是STATUS_ROLLED_BACK，回滚过程中抛出异常则是STATUS_UNKNOWN
	 */
	private void triggerAfterCompletion(DefaultTransactionStatus status, int completionStatus) {
		//如果是新事务同步
		if (status.isNewSynchronization()) {
			//获取目前注册的TransactionSynchronization集合
			List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
			//清空当前线程绑定的TransactionSynchronization集合
			TransactionSynchronizationManager.clearSynchronization();
			//如果没有事务或者是新事物
			if (!status.hasTransaction() || status.isNewTransaction()) {
				// No transaction or new transaction for the current scope ->
				// invoke the afterCompletion callbacks immediately
				//立即调用afterCompletion回调
				invokeAfterCompletion(synchronizations, completionStatus);
			} else if (!synchronizations.isEmpty()) {
				// Existing transaction that we participate in, controlled outside
				// the scope of this Spring transaction manager -> try to register
				// an afterCompletion callback with the existing (JTA) transaction.
				//否则表示有事务但不是新事务
				//我们参与的现有事务在此Spring事务管理器的范围之外进行了控制，
				//那么尝试向现有（JTA）事务注册一个afterCompletion回调。
				registerAfterCompletionWithExistingTransaction(status.getTransaction(), synchronizations);
			}
		}
	}

	/**
	 * Actually invoke the {@code afterCompletion} methods of the
	 * given Spring TransactionSynchronization objects.
	 * <p>To be called by this abstract manager itself, or by special implementations
	 * of the {@code registerAfterCompletionWithExistingTransaction} callback.
	 *
	 * @param synchronizations a List of TransactionSynchronization objects
	 * @param completionStatus the completion status according to the
	 *                         constants in the TransactionSynchronization interface
	 * @see #registerAfterCompletionWithExistingTransaction(Object, java.util.List)
	 * @see TransactionSynchronization#STATUS_COMMITTED
	 * @see TransactionSynchronization#STATUS_ROLLED_BACK
	 * @see TransactionSynchronization#STATUS_UNKNOWN
	 */
	protected final void invokeAfterCompletion(List<TransactionSynchronization> synchronizations, int completionStatus) {
		TransactionSynchronizationUtils.invokeAfterCompletion(synchronizations, completionStatus);
	}

	/**
	 * Clean up after completion, clearing synchronization if necessary,
	 * and invoking doCleanupAfterCompletion.
	 * 完成后进行清理，必要时清除同步，然后调用doCleanupAfterCompletion。
	 *
	 * @param status object representing the transaction 事务状态，代表着当前事务
	 * @see #doCleanupAfterCompletion
	 */
	private void cleanupAfterCompletion(DefaultTransactionStatus status) {
		/*
		 * 1 设置当前事务状态为已完成，即completed属性为true
		 */
		status.setCompleted();
		/*
		 * 2 如果是新同步，那么这里清除绑定到当前线程的事务信息
		 * 比如事务同步、事务名、事务只读状态、事务隔离级别、事务激活状态
		 */
		if (status.isNewSynchronization()) {
			TransactionSynchronizationManager.clear();
		}
		/*
		 * 3 如果是新事务，那么这里调用doCleanupAfterCompletion模版方法
		 * 该方法同样是由具体的子类实现的，用于扩展自己的行为。
		 */
		if (status.isNewTransaction()) {
			doCleanupAfterCompletion(status.getTransaction());
		}
		/*
		 * 4 如果存在此前已经被挂起的事务资源，那么这里需要恢复此前的资源
		 */
		if (status.getSuspendedResources() != null) {
			if (status.isDebug()) {
				logger.debug("Resuming suspended transaction after completion of inner transaction");
			}
			//获取当前的内部事务对象，比如对于DataSourceTransactionManager事务管理器
			//他创建的内部事务对象就是DataSourceTransactionObject
			Object transaction = (status.hasTransaction() ? status.getTransaction() : null);
			/*
			 * 唤醒挂起的事务资源
			 * 重新绑定之前挂起的数据库资源，重新唤醒并注册此前的同步器，重新绑定各种事务信息
			 */
			resume(transaction, (SuspendedResourcesHolder) status.getSuspendedResources());
		}
	}


	//---------------------------------------------------------------------
	// Template methods to be implemented in subclasses
	//---------------------------------------------------------------------

	/**
	 * Return a transaction object for the current transaction state.
	 * <p>The returned object will usually be specific to the concrete transaction
	 * manager implementation, carrying corresponding transaction state in a
	 * modifiable fashion. This object will be passed into the other template
	 * methods (e.g. doBegin and doCommit), either directly or as part of a
	 * DefaultTransactionStatus instance.
	 * <p>The returned object should contain information about any existing
	 * transaction, that is, a transaction that has already started before the
	 * current {@code getTransaction} call on the transaction manager.
	 * Consequently, a {@code doGetTransaction} implementation will usually
	 * look for an existing transaction and store corresponding state in the
	 * returned transaction object.
	 *
	 * @return the current transaction object
	 * @throws org.springframework.transaction.CannotCreateTransactionException if transaction support is not available
	 * @throws TransactionException                                             in case of lookup or system errors
	 * @see #doBegin
	 * @see #doCommit
	 * @see #doRollback
	 * @see DefaultTransactionStatus#getTransaction
	 */
	protected abstract Object doGetTransaction() throws TransactionException;

	/**
	 * Check if the given transaction object indicates an existing transaction
	 * (that is, a transaction which has already started).
	 * <p>The result will be evaluated according to the specified propagation
	 * behavior for the new transaction. An existing transaction might get
	 * suspended (in case of PROPAGATION_REQUIRES_NEW), or the new transaction
	 * might participate in the existing one (in case of PROPAGATION_REQUIRED).
	 * <p>The default implementation returns {@code false}, assuming that
	 * participating in existing transactions is generally not supported.
	 * Subclasses are of course encouraged to provide such support.
	 *
	 * @param transaction the transaction object returned by doGetTransaction
	 * @return if there is an existing transaction
	 * @throws TransactionException in case of system errors
	 * @see #doGetTransaction
	 */
	protected boolean isExistingTransaction(Object transaction) throws TransactionException {
		return false;
	}

	/**
	 * Return whether to use a savepoint for a nested transaction.
	 * <p>Default is {@code true}, which causes delegation to DefaultTransactionStatus
	 * for creating and holding a savepoint. If the transaction object does not implement
	 * the SavepointManager interface, a NestedTransactionNotSupportedException will be
	 * thrown. Else, the SavepointManager will be asked to create a new savepoint to
	 * demarcate the start of the nested transaction.
	 * <p>Subclasses can override this to return {@code false}, causing a further
	 * call to {@code doBegin} - within the context of an already existing transaction.
	 * The {@code doBegin} implementation needs to handle this accordingly in such
	 * a scenario. This is appropriate for JTA, for example.
	 *
	 * @see DefaultTransactionStatus#createAndHoldSavepoint
	 * @see DefaultTransactionStatus#rollbackToHeldSavepoint
	 * @see DefaultTransactionStatus#releaseHeldSavepoint
	 * @see #doBegin
	 */
	protected boolean useSavepointForNestedTransaction() {
		return true;
	}

	/**
	 * Begin a new transaction with semantics according to the given transaction
	 * definition. Does not have to care about applying the propagation behavior,
	 * as this has already been handled by this abstract manager.
	 * <p>This method gets called when the transaction manager has decided to actually
	 * start a new transaction. Either there wasn't any transaction before, or the
	 * previous transaction has been suspended.
	 * <p>A special scenario is a nested transaction without savepoint: If
	 * {@code useSavepointForNestedTransaction()} returns "false", this method
	 * will be called to start a nested transaction when necessary. In such a context,
	 * there will be an active transaction: The implementation of this method has
	 * to detect this and start an appropriate nested transaction.
	 *
	 * @param transaction the transaction object returned by {@code doGetTransaction}
	 * @param definition  a TransactionDefinition instance, describing propagation
	 *                    behavior, isolation level, read-only flag, timeout, and transaction name
	 * @throws TransactionException                                                   in case of creation or system errors
	 * @throws org.springframework.transaction.NestedTransactionNotSupportedException if the underlying transaction does not support nesting
	 */
	protected abstract void doBegin(Object transaction, TransactionDefinition definition)
			throws TransactionException;

	/**
	 * Suspend the resources of the current transaction.
	 * Transaction synchronization will already have been suspended.
	 * <p>The default implementation throws a TransactionSuspensionNotSupportedException,
	 * assuming that transaction suspension is generally not supported.
	 *
	 * @param transaction the transaction object returned by {@code doGetTransaction}
	 * @return an object that holds suspended resources
	 * (will be kept unexamined for passing it into doResume)
	 * @throws org.springframework.transaction.TransactionSuspensionNotSupportedException if suspending is not supported by the transaction manager implementation
	 * @throws TransactionException                                                       in case of system errors
	 * @see #doResume
	 */
	protected Object doSuspend(Object transaction) throws TransactionException {
		throw new TransactionSuspensionNotSupportedException(
				"Transaction manager [" + getClass().getName() + "] does not support transaction suspension");
	}

	/**
	 * Resume the resources of the current transaction.
	 * Transaction synchronization will be resumed afterwards.
	 * <p>The default implementation throws a TransactionSuspensionNotSupportedException,
	 * assuming that transaction suspension is generally not supported.
	 *
	 * @param transaction        the transaction object returned by {@code doGetTransaction}
	 * @param suspendedResources the object that holds suspended resources,
	 *                           as returned by doSuspend
	 * @throws org.springframework.transaction.TransactionSuspensionNotSupportedException if resuming is not supported by the transaction manager implementation
	 * @throws TransactionException                                                       in case of system errors
	 * @see #doSuspend
	 */
	protected void doResume(@Nullable Object transaction, Object suspendedResources) throws TransactionException {
		throw new TransactionSuspensionNotSupportedException(
				"Transaction manager [" + getClass().getName() + "] does not support transaction suspension");
	}

	/**
	 * Return whether to call {@code doCommit} on a transaction that has been
	 * marked as rollback-only in a global fashion.
	 * <p>Does not apply if an application locally sets the transaction to rollback-only
	 * via the TransactionStatus, but only to the transaction itself being marked as
	 * rollback-only by the transaction coordinator.
	 * <p>Default is "false": Local transaction strategies usually don't hold the rollback-only
	 * marker in the transaction itself, therefore they can't handle rollback-only transactions
	 * as part of transaction commit. Hence, AbstractPlatformTransactionManager will trigger
	 * a rollback in that case, throwing an UnexpectedRollbackException afterwards.
	 * <p>Override this to return "true" if the concrete transaction manager expects a
	 * {@code doCommit} call even for a rollback-only transaction, allowing for
	 * special handling there. This will, for example, be the case for JTA, where
	 * {@code UserTransaction.commit} will check the read-only flag itself and
	 * throw a corresponding RollbackException, which might include the specific reason
	 * (such as a transaction timeout).
	 * <p>If this method returns "true" but the {@code doCommit} implementation does not
	 * throw an exception, this transaction manager will throw an UnexpectedRollbackException
	 * itself. This should not be the typical case; it is mainly checked to cover misbehaving
	 * JTA providers that silently roll back even when the rollback has not been requested
	 * by the calling code.
	 *
	 * @see #doCommit
	 * @see DefaultTransactionStatus#isGlobalRollbackOnly()
	 * @see DefaultTransactionStatus#isLocalRollbackOnly()
	 * @see org.springframework.transaction.TransactionStatus#setRollbackOnly()
	 * @see org.springframework.transaction.UnexpectedRollbackException
	 * @see jakarta.transaction.UserTransaction#commit()
	 * @see jakarta.transaction.RollbackException
	 */
	protected boolean shouldCommitOnGlobalRollbackOnly() {
		return false;
	}

	/**
	 * Make preparations for commit, to be performed before the
	 * {@code beforeCommit} synchronization callbacks occur.
	 * <p>Note that exceptions will get propagated to the commit caller
	 * and cause a rollback of the transaction.
	 *
	 * @param status the status representation of the transaction
	 * @throws RuntimeException in case of errors; will be <b>propagated to the caller</b>
	 *                          (note: do not throw TransactionException subclasses here!)
	 */
	protected void prepareForCommit(DefaultTransactionStatus status) {
	}

	/**
	 * Perform an actual commit of the given transaction.
	 * <p>An implementation does not need to check the "new transaction" flag
	 * or the rollback-only flag; this will already have been handled before.
	 * Usually, a straight commit will be performed on the transaction object
	 * contained in the passed-in status.
	 *
	 * @param status the status representation of the transaction
	 * @throws TransactionException in case of commit or system errors
	 * @see DefaultTransactionStatus#getTransaction
	 */
	protected abstract void doCommit(DefaultTransactionStatus status) throws TransactionException;

	/**
	 * Perform an actual rollback of the given transaction.
	 * <p>An implementation does not need to check the "new transaction" flag;
	 * this will already have been handled before. Usually, a straight rollback
	 * will be performed on the transaction object contained in the passed-in status.
	 *
	 * @param status the status representation of the transaction
	 * @throws TransactionException in case of system errors
	 * @see DefaultTransactionStatus#getTransaction
	 */
	protected abstract void doRollback(DefaultTransactionStatus status) throws TransactionException;

	/**
	 * Set the given transaction rollback-only. Only called on rollback
	 * if the current transaction participates in an existing one.
	 * <p>The default implementation throws an IllegalTransactionStateException,
	 * assuming that participating in existing transactions is generally not
	 * supported. Subclasses are of course encouraged to provide such support.
	 *
	 * @param status the status representation of the transaction
	 * @throws TransactionException in case of system errors
	 */
	protected void doSetRollbackOnly(DefaultTransactionStatus status) throws TransactionException {
		throw new IllegalTransactionStateException(
				"Participating in existing transactions is not supported - when 'isExistingTransaction' " +
						"returns true, appropriate 'doSetRollbackOnly' behavior must be provided");
	}

	/**
	 * Register the given list of transaction synchronizations with the existing transaction.
	 * <p>Invoked when the control of the Spring transaction manager and thus all Spring
	 * transaction synchronizations end, without the transaction being completed yet. This
	 * is for example the case when participating in an existing JTA or EJB CMT transaction.
	 * <p>The default implementation simply invokes the {@code afterCompletion} methods
	 * immediately, passing in "STATUS_UNKNOWN". This is the best we can do if there's no
	 * chance to determine the actual outcome of the outer transaction.
	 *
	 * @param transaction      the transaction object returned by {@code doGetTransaction}
	 * @param synchronizations a List of TransactionSynchronization objects
	 * @throws TransactionException in case of system errors
	 * @see #invokeAfterCompletion(java.util.List, int)
	 * @see TransactionSynchronization#afterCompletion(int)
	 * @see TransactionSynchronization#STATUS_UNKNOWN
	 */
	protected void registerAfterCompletionWithExistingTransaction(
			Object transaction, List<TransactionSynchronization> synchronizations) throws TransactionException {

		logger.debug("Cannot register Spring after-completion synchronization with existing transaction - " +
				"processing Spring after-completion callbacks immediately, with outcome status 'unknown'");
		invokeAfterCompletion(synchronizations, TransactionSynchronization.STATUS_UNKNOWN);
	}

	/**
	 * Cleanup resources after transaction completion.
	 * <p>Called after {@code doCommit} and {@code doRollback} execution,
	 * on any outcome. The default implementation does nothing.
	 * <p>Should not throw any exceptions but just issue warnings on errors.
	 *
	 * @param transaction the transaction object returned by {@code doGetTransaction}
	 */
	protected void doCleanupAfterCompletion(Object transaction) {
	}


	//---------------------------------------------------------------------
	// Serialization support
	//---------------------------------------------------------------------

	private void readObject(ObjectInputStream ois) throws IOException, ClassNotFoundException {
		// Rely on default serialization; just initialize state after deserialization.
		ois.defaultReadObject();

		// Initialize transient fields.
		this.logger = LogFactory.getLog(getClass());
	}


	/**
	 * Holder for suspended resources.
	 * Used internally by {@code suspend} and {@code resume}.
	 */
	protected static final class SuspendedResourcesHolder {

		@Nullable
		private final Object suspendedResources;

		@Nullable
		private List<TransactionSynchronization> suspendedSynchronizations;

		@Nullable
		private String name;

		private boolean readOnly;

		@Nullable
		private Integer isolationLevel;

		private boolean wasActive;

		private SuspendedResourcesHolder(Object suspendedResources) {
			this.suspendedResources = suspendedResources;
		}

		private SuspendedResourcesHolder(
				@Nullable Object suspendedResources, List<TransactionSynchronization> suspendedSynchronizations,
				@Nullable String name, boolean readOnly, @Nullable Integer isolationLevel, boolean wasActive) {

			this.suspendedResources = suspendedResources;
			this.suspendedSynchronizations = suspendedSynchronizations;
			this.name = name;
			this.readOnly = readOnly;
			this.isolationLevel = isolationLevel;
			this.wasActive = wasActive;
		}
	}

}
