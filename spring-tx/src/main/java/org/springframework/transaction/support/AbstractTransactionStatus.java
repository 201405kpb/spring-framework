/*
 * Copyright 2002-2019 the original author or authors.
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

import org.springframework.lang.Nullable;
import org.springframework.transaction.NestedTransactionNotSupportedException;
import org.springframework.transaction.SavepointManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionUsageException;

/**
 * Abstract base implementation of the
 * {@link org.springframework.transaction.TransactionStatus} interface.
 *
 * <p>Pre-implements the handling of local rollback-only and completed flags, and
 * delegation to an underlying {@link org.springframework.transaction.SavepointManager}.
 * Also offers the option of a holding a savepoint within the transaction.
 *
 * <p>Does not assume any specific internal transaction handling, such as an
 * underlying transaction object, and no transaction synchronization mechanism.
 *
 * @author Juergen Hoeller
 * @since 1.2.3
 * @see #setRollbackOnly()
 * @see #isRollbackOnly()
 * @see #setCompleted()
 * @see #isCompleted()
 * @see #getSavepointManager()
 * @see SimpleTransactionStatus
 * @see DefaultTransactionStatus
 */
public abstract class AbstractTransactionStatus implements TransactionStatus {

	private boolean rollbackOnly = false;

	private boolean completed = false;

	@Nullable
	private Object savepoint;


	//---------------------------------------------------------------------
	// Implementation of TransactionExecution
	//---------------------------------------------------------------------

	@Override
	public void setRollbackOnly() {
		this.rollbackOnly = true;
	}

	/**
	 * Determine the rollback-only flag via checking both the local rollback-only flag
	 * of this TransactionStatus and the global rollback-only flag of the underlying
	 * transaction, if any.
	 * @see #isLocalRollbackOnly()
	 * @see #isGlobalRollbackOnly()
	 */
	@Override
	public boolean isRollbackOnly() {
		return (isLocalRollbackOnly() || isGlobalRollbackOnly());
	}

	/**
	 * Determine the rollback-only flag via checking this TransactionStatus.
	 * <p>Will only return "true" if the application called {@code setRollbackOnly}
	 * on this TransactionStatus object.
	 */
	public boolean isLocalRollbackOnly() {
		return this.rollbackOnly;
	}

	/**
	 * Template method for determining the global rollback-only flag of the
	 * underlying transaction, if any.
	 * <p>This implementation always returns {@code false}.
	 */
	public boolean isGlobalRollbackOnly() {
		return false;
	}

	/**
	 * Mark this transaction as completed, that is, committed or rolled back.
	 */
	public void setCompleted() {
		this.completed = true;
	}

	@Override
	public boolean isCompleted() {
		return this.completed;
	}


	//---------------------------------------------------------------------
	// Handling of current savepoint state
	//---------------------------------------------------------------------

	@Override
	public boolean hasSavepoint() {
		return (this.savepoint != null);
	}

	/**
	 * Set a savepoint for this transaction. Useful for PROPAGATION_NESTED.
	 * @see org.springframework.transaction.TransactionDefinition#PROPAGATION_NESTED
	 */
	protected void setSavepoint(@Nullable Object savepoint) {
		this.savepoint = savepoint;
	}

	/**
	 * Get the savepoint for this transaction, if any.
	 */
	@Nullable
	protected Object getSavepoint() {
		return this.savepoint;
	}

	/**
	 * Create a savepoint and hold it for the transaction.
	 * @throws org.springframework.transaction.NestedTransactionNotSupportedException
	 * if the underlying transaction does not support savepoints
	 */
	public void createAndHoldSavepoint() throws TransactionException {
		setSavepoint(getSavepointManager().createSavepoint());
	}

	/**
	 * Roll back to the savepoint that is held for the transaction
	 * and release the savepoint right afterwards.
	 * 我们在invokeWithinTransaction方法中知道，completeTransactionAfterThrowing
	 * 方法执行完毕仍然会抛出异常到外层事务中，因此仍然可能导致外层事务的回滚
	 */
	public void rollbackToHeldSavepoint() throws TransactionException {
		//获取保存点
		Object savepoint = getSavepoint();
		if (savepoint == null) {
			throw new TransactionUsageException(
					"Cannot roll back to savepoint - no savepoint associated with current transaction");
		}
		//使用保存点管理器来执行回滚保存点的操作，实际上SavepointManager就是当前的内部事务对象
		//比如DataSourceTransactionManager的DataSourceTransactionObject
		//DataSourceTransactionObject继承了JdbcTransactionObjectSupport

		//回滚操作
		getSavepointManager().rollbackToSavepoint(savepoint);
		//释放保存点
		getSavepointManager().releaseSavepoint(savepoint);
		//设置保存点为null
		setSavepoint(null);
	}

	/**
	 * Release the savepoint that is held for the transaction.
	 * 释放为该事务保留的保存点，因为此保存点中的代码已正常执行完毕
	 */
	public void releaseHeldSavepoint() throws TransactionException {
		//获取保存点
		Object savepoint = getSavepoint();
		if (savepoint == null) {
			throw new TransactionUsageException(
					"Cannot release savepoint - no savepoint associated with current transaction");
		}
		//释放保存点，该方法我们此前就讲过了
		//实际上就是获取连接Connection并调用releaseSavepoint方法参数为当前的保存点
		getSavepointManager().releaseSavepoint(savepoint);
		//将保存点设置为null
		setSavepoint(null);
	}


	//---------------------------------------------------------------------
	// Implementation of SavepointManager
	//---------------------------------------------------------------------

	/**
	 * This implementation delegates to a SavepointManager for the
	 * underlying transaction, if possible.
	 * @see #getSavepointManager()
	 * @see SavepointManager#createSavepoint()
	 */
	@Override
	public Object createSavepoint() throws TransactionException {
		return getSavepointManager().createSavepoint();
	}

	/**
	 * This implementation delegates to a SavepointManager for the
	 * underlying transaction, if possible.
	 * @see #getSavepointManager()
	 * @see SavepointManager#rollbackToSavepoint(Object)
	 */
	@Override
	public void rollbackToSavepoint(Object savepoint) throws TransactionException {
		getSavepointManager().rollbackToSavepoint(savepoint);
	}

	/**
	 * This implementation delegates to a SavepointManager for the
	 * underlying transaction, if possible.
	 * @see #getSavepointManager()
	 * @see SavepointManager#releaseSavepoint(Object)
	 */
	@Override
	public void releaseSavepoint(Object savepoint) throws TransactionException {
		getSavepointManager().releaseSavepoint(savepoint);
	}

	/**
	 * Return a SavepointManager for the underlying transaction, if possible.
	 * <p>Default implementation always throws a NestedTransactionNotSupportedException.
	 * @throws org.springframework.transaction.NestedTransactionNotSupportedException
	 * if the underlying transaction does not support savepoints
	 */
	protected SavepointManager getSavepointManager() {
		throw new NestedTransactionNotSupportedException("This transaction does not support savepoints");
	}


	//---------------------------------------------------------------------
	// Flushing support
	//---------------------------------------------------------------------

	/**
	 * This implementation is empty, considering flush as a no-op.
	 */
	@Override
	public void flush() {
	}

}
