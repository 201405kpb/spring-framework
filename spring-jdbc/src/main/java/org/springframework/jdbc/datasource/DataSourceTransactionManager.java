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

package org.springframework.jdbc.datasource;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.lang.Nullable;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.ResourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;
import org.springframework.util.Assert;

/**
 * {@link org.springframework.transaction.PlatformTransactionManager}
 * implementation for a single JDBC {@link javax.sql.DataSource}. This class is
 * capable of working in any environment with any JDBC driver, as long as the setup
 * uses a {@code javax.sql.DataSource} as its {@code Connection} factory mechanism.
 * Binds a JDBC Connection from the specified DataSource to the current thread,
 * potentially allowing for one thread-bound Connection per DataSource.
 *
 * <p><b>Note: The DataSource that this transaction manager operates on needs
 * to return independent Connections.</b> The Connections may come from a pool
 * (the typical case), but the DataSource must not return thread-scoped /
 * request-scoped Connections or the like. This transaction manager will
 * associate Connections with thread-bound transactions itself, according
 * to the specified propagation behavior. It assumes that a separate,
 * independent Connection can be obtained even during an ongoing transaction.
 *
 * <p>Application code is required to retrieve the JDBC Connection via
 * {@link DataSourceUtils#getConnection(DataSource)} instead of a standard
 * Jakarta EE-style {@link DataSource#getConnection()} call. Spring classes such as
 * {@link org.springframework.jdbc.core.JdbcTemplate} use this strategy implicitly.
 * If not used in combination with this transaction manager, the
 * {@link DataSourceUtils} lookup strategy behaves exactly like the native
 * DataSource lookup; it can thus be used in a portable fashion.
 *
 * <p>Alternatively, you can allow application code to work with the standard
 * Jakarta EE-style lookup pattern {@link DataSource#getConnection()}, for example for
 * legacy code that is not aware of Spring at all. In that case, define a
 * {@link TransactionAwareDataSourceProxy} for your target DataSource, and pass
 * that proxy DataSource to your DAOs, which will automatically participate in
 * Spring-managed transactions when accessing it.
 *
 * <p>Supports custom isolation levels, and timeouts which get applied as
 * appropriate JDBC statement timeouts. To support the latter, application code
 * must either use {@link org.springframework.jdbc.core.JdbcTemplate}, call
 * {@link DataSourceUtils#applyTransactionTimeout} for each created JDBC Statement,
 * or go through a {@link TransactionAwareDataSourceProxy} which will create
 * timeout-aware JDBC Connections and Statements automatically.
 *
 * <p>Consider defining a {@link LazyConnectionDataSourceProxy} for your target
 * DataSource, pointing both this transaction manager and your DAOs to it.
 * This will lead to optimized handling of "empty" transactions, i.e. of transactions
 * without any JDBC statements executed. A LazyConnectionDataSourceProxy will not fetch
 * an actual JDBC Connection from the target DataSource until a Statement gets executed,
 * lazily applying the specified transaction settings to the target Connection.
 *
 * <p>This transaction manager supports nested transactions via the JDBC 3.0
 * {@link java.sql.Savepoint} mechanism. The
 * {@link #setNestedTransactionAllowed "nestedTransactionAllowed"} flag defaults
 * to "true", since nested transactions will work without restrictions on JDBC
 * drivers that support savepoints (such as the Oracle JDBC driver).
 *
 * <p>This transaction manager can be used as a replacement for the
 * {@link org.springframework.transaction.jta.JtaTransactionManager} in the single
 * resource case, as it does not require a container that supports JTA, typically
 * in combination with a locally defined JDBC DataSource (e.g. an Apache Commons
 * DBCP connection pool). Switching between this local strategy and a JTA
 * environment is just a matter of configuration!
 *
 * <p>As of 4.3.4, this transaction manager triggers flush callbacks on registered
 * transaction synchronizations (if synchronization is generally active), assuming
 * resources operating on the underlying JDBC {@code Connection}. This allows for
 * setup analogous to {@code JtaTransactionManager}, in particular with respect to
 * lazily registered ORM resources (e.g. a Hibernate {@code Session}).
 *
 * <p><b>NOTE: As of 5.3, {@link org.springframework.jdbc.support.JdbcTransactionManager}
 * is available as an extended subclass which includes commit/rollback exception
 * translation, aligned with {@link org.springframework.jdbc.core.JdbcTemplate}.</b>
 *
 * @author Juergen Hoeller
 * @see #setNestedTransactionAllowed
 * @see java.sql.Savepoint
 * @see DataSourceUtils#getConnection(javax.sql.DataSource)
 * @see DataSourceUtils#applyTransactionTimeout
 * @see DataSourceUtils#releaseConnection
 * @see TransactionAwareDataSourceProxy
 * @see LazyConnectionDataSourceProxy
 * @see org.springframework.jdbc.core.JdbcTemplate
 * @since 02.05.2003
 */
@SuppressWarnings("serial")
public class DataSourceTransactionManager extends AbstractPlatformTransactionManager
		implements ResourceTransactionManager, InitializingBean {

	@Nullable
	private DataSource dataSource;

	private boolean enforceReadOnly = false;


	/**
	 * Create a new DataSourceTransactionManager instance.
	 * A DataSource has to be set to be able to use it.
	 *
	 * @see #setDataSource
	 */
	public DataSourceTransactionManager() {
		setNestedTransactionAllowed(true);
	}

	/**
	 * Create a new DataSourceTransactionManager instance.
	 *
	 * @param dataSource the JDBC DataSource to manage transactions for
	 */
	public DataSourceTransactionManager(DataSource dataSource) {
		this();
		setDataSource(dataSource);
		afterPropertiesSet();
	}


	/**
	 * Set the JDBC DataSource that this instance should manage transactions for.
	 * <p>This will typically be a locally defined DataSource, for example an
	 * Apache Commons DBCP connection pool. Alternatively, you can also drive
	 * transactions for a non-XA J2EE DataSource fetched from JNDI. For an XA
	 * DataSource, use JtaTransactionManager.
	 * <p>The DataSource specified here should be the target DataSource to manage
	 * transactions for, not a TransactionAwareDataSourceProxy. Only data access
	 * code may work with TransactionAwareDataSourceProxy, while the transaction
	 * manager needs to work on the underlying target DataSource. If there's
	 * nevertheless a TransactionAwareDataSourceProxy passed in, it will be
	 * unwrapped to extract its target DataSource.
	 * <p><b>The DataSource passed in here needs to return independent Connections.</b>
	 * The Connections may come from a pool (the typical case), but the DataSource
	 * must not return thread-scoped / request-scoped Connections or the like.
	 *
	 * @see TransactionAwareDataSourceProxy
	 * @see org.springframework.transaction.jta.JtaTransactionManager
	 */
	public void setDataSource(@Nullable DataSource dataSource) {
		if (dataSource instanceof TransactionAwareDataSourceProxy) {
			// If we got a TransactionAwareDataSourceProxy, we need to perform transactions
			// for its underlying target DataSource, else data access code won't see
			// properly exposed transactions (i.e. transactions for the target DataSource).
			this.dataSource = ((TransactionAwareDataSourceProxy) dataSource).getTargetDataSource();
		} else {
			this.dataSource = dataSource;
		}
	}

	/**
	 * Return the JDBC DataSource that this instance manages transactions for.
	 */
	@Nullable
	public DataSource getDataSource() {
		return this.dataSource;
	}

	/**
	 * Obtain the DataSource for actual use.
	 *
	 * @return the DataSource (never {@code null})
	 * @throws IllegalStateException in case of no DataSource set
	 * @since 5.0
	 */
	protected DataSource obtainDataSource() {
		DataSource dataSource = getDataSource();
		Assert.state(dataSource != null, "No DataSource set");
		return dataSource;
	}

	/**
	 * Specify whether to enforce the read-only nature of a transaction
	 * (as indicated by {@link TransactionDefinition#isReadOnly()})
	 * through an explicit statement on the transactional connection:
	 * "SET TRANSACTION READ ONLY" as understood by Oracle, MySQL and Postgres.
	 * <p>The exact treatment, including any SQL statement executed on the connection,
	 * can be customized through {@link #prepareTransactionalConnection}.
	 * <p>This mode of read-only handling goes beyond the {@link Connection#setReadOnly}
	 * hint that Spring applies by default. In contrast to that standard JDBC hint,
	 * "SET TRANSACTION READ ONLY" enforces an isolation-level-like connection mode
	 * where data manipulation statements are strictly disallowed. Also, on Oracle,
	 * this read-only mode provides read consistency for the entire transaction.
	 * <p>Note that older Oracle JDBC drivers (9i, 10g) used to enforce this read-only
	 * mode even for {@code Connection.setReadOnly(true}. However, with recent drivers,
	 * this strong enforcement needs to be applied explicitly, e.g. through this flag.
	 *
	 * @see #prepareTransactionalConnection
	 * @since 4.3.7
	 */
	public void setEnforceReadOnly(boolean enforceReadOnly) {
		this.enforceReadOnly = enforceReadOnly;
	}

	/**
	 * Return whether to enforce the read-only nature of a transaction
	 * through an explicit statement on the transactional connection.
	 *
	 * @see #setEnforceReadOnly
	 * @since 4.3.7
	 */
	public boolean isEnforceReadOnly() {
		return this.enforceReadOnly;
	}

	@Override
	public void afterPropertiesSet() {
		if (getDataSource() == null) {
			throw new IllegalArgumentException("Property 'dataSource' is required");
		}
	}


	@Override
	public Object getResourceFactory() {
		return obtainDataSource();
	}

	/**
	 * 返回当前事务状态的事务对象，返回的对象通常特定于具体的事务管理器实现。
	 * 返回的对象应包含有关任何现有事务的信息，即，在事务管理器上的当前getTransaction方法调用之前已经启动的事务。
	 * 因此，doGetTransaction的实现通常是将查找现有事务并将相应的状态存储在返回的事务对象中。
	 * <p>
	 * 对于DataSourceTransactionManager，将会返回DataSourceTransactionObject
	 *
	 * @return 一个DataSourceTransactionObject对象
	 */
	@Override
	protected Object doGetTransaction() {
		//创建一个DataSourceTransactionObject，由DataSourceTransactionManager用作内部事务对象。
		//内部可能会持有一个ConnectionHolder对象，还具有创建、回滚、释放保存点的功能
		DataSourceTransactionObject txObject = new DataSourceTransactionObject();
		//设置是否允许保存点，DataSourceTransactionManager默认会允许，用于实现嵌套事务
		txObject.setSavepointAllowed(isNestedTransactionAllowed());
		//obtainDataSource方法用于获取配置的数据源，就是我们自己配置的数据源
		//getResource用于获取此线程在当前数据源中已拥有JDBC连接资源持有者ConnectionHolder
		//如果此前没有获取过连接，则返回null；如果此前开启了过事务（外层事务），那么肯定不会获取null
		ConnectionHolder conHolder =
				(ConnectionHolder) TransactionSynchronizationManager.getResource(obtainDataSource());
		//设置连接信息，newConnectionHolder属性设置为false，这表示默认此前已经存在ConnectionHolder
		//但实际上可能并没有，因此后面的步骤中会再次判断该值
		txObject.setConnectionHolder(conHolder, false);
		return txObject;
	}

	/**
	 * 检查是否有现有事务（即已经开启过事务）。
	 *
	 * @param transaction 此前获取的事务对象
	 * @return 如果已存在事务则返回true，否则返回false
	 */
	@Override
	protected boolean isExistingTransaction(Object transaction) {
		//强转为DataSourceTransactionObject
		DataSourceTransactionObject txObject = (DataSourceTransactionObject) transaction;
		//判断内部的数据库连接connectionHolder是否不为null并且已经开启了事务
		return (txObject.hasConnectionHolder() && txObject.getConnectionHolder().isTransactionActive());
	}

	/**
	 * 开启新事物
	 * 并不会处理事务的传播行为，因为传播行为是Spring提供的特性，在事务管理器中就被直接处理了
	 *
	 * @param transaction 此前的doGetTransaction方法返回的事务对象，也就是DataSourceTransactionObject
	 * @param definition  一个TransactionDefinition实例，描述传播行为，隔离级别，只读标志，超时时间和事务名称等属性
	 */
	@Override
	protected void doBegin(Object transaction, TransactionDefinition definition) {
		//强制转型
		DataSourceTransactionObject txObject = (DataSourceTransactionObject) transaction;
		Connection con = null;
		try {
			//如果不存在事务连接资源持有者属性，或者资源标记为与事务同步
			//简单的说就还没有获取连接，那么这里从数据源中获取一个新连接
			//第一次进入事务方法时默认就会走该逻辑
			if (!txObject.hasConnectionHolder() ||
					txObject.getConnectionHolder().isSynchronizedWithTransaction()) {
				//从我们配置的数据源中获取一个新连接
				Connection newCon = obtainDataSource().getConnection();
				if (logger.isDebugEnabled()) {
					logger.debug("Acquired Connection [" + newCon + "] for JDBC transaction");
				}
				/*
				 * 新建一个ConnectionHolder对象，其内部保存这获取的连接，
				 * 将使用SimpleConnectionHandle包装获取的连接并且设置为connectionHandle属性
				 *
				 * 该ConnectionHolder被设置给txObject的connectionHolder属性
				 * 以及将newConnectionHolder属性设置为true，表示是一个新连接
				 *
				 * 到这里，事务对象就已经获得了一个新连接
				 */
				txObject.setConnectionHolder(new ConnectionHolder(newCon), true);
			}
			//获取事务对象的连接持有者，将synchronizedWithTransaction设置为true，即资源标记为与事务同步。
			txObject.getConnectionHolder().setSynchronizedWithTransaction(true);
			//获取内部保存的连接
			con = txObject.getConnectionHolder().getConnection();
			/*
			 * 使用给定的事务语义准备给定的Connection，就是设置数据库事务的隔离级别，只读标志属性
			 *
			 * 如果我们配置的隔离级别属性是ISOLATION_DEFAULT，即采用默认隔离级别，或者不是默认的隔离级别但是与连接的隔离级别一致，那么将返回null
			 * 否则将返回从连接中直接获取的隔离级别（如果有）
			 */
			Integer previousIsolationLevel = DataSourceUtils.prepareConnectionForTransaction(con, definition);
			//此前的隔离级别设置给事务对象的previousIsolationLevel属性
			txObject.setPreviousIsolationLevel(previousIsolationLevel);
			//只读标志设置给事务对象的readOnly属性
			txObject.setReadOnly(definition.isReadOnly());

			//如有必要，切换为手动提交。
			//从Druid数据源中获取的连接DruidPooledConnection就是默认自动提交，即getAutoCommit返回true
			if (con.getAutoCommit()) {
				txObject.setMustRestoreAutoCommit(true);
				if (logger.isDebugEnabled()) {
					logger.debug("Switching JDBC Connection [" + con + "] to manual commit");
				}
				/*
				 * 如果上面判断是自动提交，那么切换为手动提交，为什么呢？如果不手动提交，
				 * 那么一个方法中执行多个sql语句时将会每执行一个提交一次，无法实现事务的控制
				 * 开启手动提交就能实现方法级别的整体事务控制
				 *
				 * 并且，开启手动提交时，将会自动开启事物
				 */
				con.setAutoCommit(false);
			}
			//事务已经开启，此后的sql语句，如果没有手动commit，那么将不会真正的提交给数据库
			//用户本次对数据库开始进行操作到用户执行commit命令之间的一系列操作为一个完整的事务周期。

			/*
			 *
			 * 事务开始后立即准备事务连接，主要是对于只读事务的优化操作（需要手动开启）
			 * 如果将"enforceReadOnly"标志设置为true（默认为false），并且事务定义指示只读事务，
			 * 则默认实现将执行"SET TRANSACTION READ ONLY"这一个sql语句。

			 * 请注意mysql只读事务不要开启，oracle的只读事务可以开启
			 */
			prepareTransactionalConnection(con, definition);
			//设置事务ConnectionHolder的transactionActive属性为true，表示激活当前连接的事务
			//此前判断是否有开启事务的isExistingTransaction方法就会判断这个属性
			txObject.getConnectionHolder().setTransactionActive(true);

			/*
			 * 设置实际超时时间
			 */
			int timeout = determineTimeout(definition);
			//如果不是默认超时时间-1，那么将
			if (timeout != TransactionDefinition.TIMEOUT_DEFAULT) {
				//那么设置超时时间，实际上就是根据设置的值和当前时间转换为未来的毫秒值并创建新Date配置给deadline属性
				//在其他数据库操作框架操作时将会获取该参数
				txObject.getConnectionHolder().setTimeoutInSeconds(timeout);
			}

			//如果是新的连接持有者，即newConnectionHolder属性为true
			if (txObject.isNewConnectionHolder()) {
				//绑定ConnectionHolder资源到TransactionSynchronizationManager的resources属性中
				//key就是当前的属性源，value就是ConnectionHolder
				TransactionSynchronizationManager.bindResource(obtainDataSource(), txObject.getConnectionHolder());
			}
		} catch (Throwable ex) {
			//如果是新连接，那么释放链接
			if (txObject.isNewConnectionHolder()) {
				DataSourceUtils.releaseConnection(con, obtainDataSource());
				txObject.setConnectionHolder(null, false);
			}
			throw new CannotCreateTransactionException("Could not open JDBC Connection for transaction", ex);
		}
	}

	/**
	 * 挂起当前事务，返回当前的连接资源
	 *
	 * @param transaction 挂起事务
	 * @return 被挂起的资源
	 */
	@Override
	protected Object doSuspend(Object transaction) {
		DataSourceTransactionObject txObject = (DataSourceTransactionObject) transaction;
		//将当前的事务对象的connectionHolder设置为null
		txObject.setConnectionHolder(null);
		//将当前线程的绑定的当前数据源对应的连接同样移除，并且返回被移除的连接资源
		return TransactionSynchronizationManager.unbindResource(obtainDataSource());
	}

	/**
	 * @param transaction        当前事务，DataSourceTransactionManager的实现中，该属性没有用到
	 * @param suspendedResources 被挂起的资源
	 */
	@Override
	protected void doResume(@Nullable Object transaction, Object suspendedResources) {
		//重新将此前挂起的事务以当前数据源为key绑定到当前线程的事务，bindResource方法我们此前就见过了
		//这就表示"激活"了这个挂起的事务，是不是很简单？
		TransactionSynchronizationManager.bindResource(obtainDataSource(), suspendedResources);
	}

	/**
	 * 真正的提交事务
	 *
	 * @param status 当前事务对象
	 */
	@Override
	protected void doCommit(DefaultTransactionStatus status) {
		//获取内部事务对象
		DataSourceTransactionObject txObject = (DataSourceTransactionObject) status.getTransaction();
		//获取内部的连接
		Connection con = txObject.getConnectionHolder().getConnection();
		if (status.isDebug()) {
			logger.debug("Committing JDBC transaction on Connection [" + con + "]");
		}
		try {
			//很简单，调用Connection的commit方法执行提交
			con.commit();
		} catch (SQLException ex) {
			throw new TransactionSystemException("Could not commit JDBC transaction", ex);
		}
	}


	@Override
	protected void doRollback(DefaultTransactionStatus status) {
		//获取内部事务对象
		DataSourceTransactionObject txObject = (DataSourceTransactionObject) status.getTransaction();
		//获取内部的连接
		Connection con = txObject.getConnectionHolder().getConnection();
		if (status.isDebug()) {
			logger.debug("Rolling back JDBC transaction on Connection [" + con + "]");
		}
		try {
			//很简单，调用Connection的rollback方法执行回滚
			con.rollback();
		} catch (SQLException ex) {
			throw translateException("JDBC rollback", ex);
		}
	}

	/**
	 * 设置给定的事务仅回滚。仅当当前事务参与现有事务时才回滚调用。
	 *
	 * @param status 现有的事务
	 */
	@Override
	protected void doSetRollbackOnly(DefaultTransactionStatus status) {
		DataSourceTransactionObject txObject = (DataSourceTransactionObject) status.getTransaction();
		if (status.isDebug()) {
			logger.debug("Setting JDBC transaction [" + txObject.getConnectionHolder().getConnection() +
					"] rollback-only");
		}
		//设置为仅回滚，就是设置ResourceHolderSupport的rollbackOnly属性为true，外层事务也将必定回滚
		txObject.setRollbackOnly();
	}

	/**
	 * @param transaction 当前内部事务对象
	 */
	@Override
	protected void doCleanupAfterCompletion(Object transaction) {

		DataSourceTransactionObject txObject = (DataSourceTransactionObject) transaction;

		// 如果是新获取的连接
		if (txObject.isNewConnectionHolder()) {
			//那么将绑定的当前线程的指定key的连接资源解绑
			TransactionSynchronizationManager.unbindResource(obtainDataSource());
		}
		//重置连接

		//获取连接
		Connection con = txObject.getConnectionHolder().getConnection();
		try {
			//重置连接属性为自动提交
			if (txObject.isMustRestoreAutoCommit()) {
				con.setAutoCommit(true);
			}
			//在事务处理后重置给定的Connection的只读标志和隔离级别属性
			DataSourceUtils.resetConnectionAfterTransaction(
					con, txObject.getPreviousIsolationLevel(), txObject.isReadOnly());
		} catch (Throwable ex) {
			logger.debug("Could not reset JDBC Connection after transaction", ex);
		}
		//如果是新获取的连接
		if (txObject.isNewConnectionHolder()) {
			if (logger.isDebugEnabled()) {
				logger.debug("Releasing JDBC Connection [" + con + "] after transaction");
			}
			//释放连接，根据DataSource以及Collection的具体的实现做出不同的操作
			//比如对于DruidPooledConnection，它的close方法就被重写，就可能实现连接的回收利用而不是真正的释放
			DataSourceUtils.releaseConnection(con, this.dataSource);
		}
		//清除当前连接持有者的属性，比如transactionActive重置为false，rollbackOnly置为false等等
		txObject.getConnectionHolder().clear();
	}


	/**
	 * Prepare the transactional {@code Connection} right after transaction begin.
	 * <p>The default implementation executes a "SET TRANSACTION READ ONLY" statement
	 * if the {@link #setEnforceReadOnly "enforceReadOnly"} flag is set to {@code true}
	 * and the transaction definition indicates a read-only transaction.
	 * <p>The "SET TRANSACTION READ ONLY" is understood by Oracle, MySQL and Postgres
	 * and may work with other databases as well. If you'd like to adapt this treatment,
	 * override this method accordingly.
	 * 如果将"enforceReadOnly"标志设置为true，并且事务定义指示只读事务，则默认实现将执行"SET TRANSACTION READ ONLY"sql语句。
	 *
	 * @param con        the transactional JDBC Connection
	 * @param definition the current transaction definition
	 * @throws SQLException if thrown by JDBC API
	 * @see #setEnforceReadOnly
	 * @since 4.3.7
	 */
	protected void prepareTransactionalConnection(Connection con, TransactionDefinition definition)
			throws SQLException {
		//如果将"enforceReadOnly"标志设置为true，并且事务定义指示只读事务
		if (isEnforceReadOnly() && definition.isReadOnly()) {
			//那么获取Statement，并且执行"SET TRANSACTION READ ONLY"sql语句
			try (Statement stmt = con.createStatement()) {
				stmt.executeUpdate("SET TRANSACTION READ ONLY");
			}
		}
	}

	/**
	 * Translate the given JDBC commit/rollback exception to a common Spring
	 * exception to propagate from the {@link #commit}/{@link #rollback} call.
	 * <p>The default implementation throws a {@link TransactionSystemException}.
	 * Subclasses may specifically identify concurrency failures etc.
	 *
	 * @param task the task description (commit or rollback)
	 * @param ex   the SQLException thrown from commit/rollback
	 * @return the translated exception to throw, either a
	 * {@link org.springframework.dao.DataAccessException} or a
	 * {@link org.springframework.transaction.TransactionException}
	 * @since 5.3
	 */
	protected RuntimeException translateException(String task, SQLException ex) {
		return new TransactionSystemException(task + " failed", ex);
	}


	/**
	 * DataSource transaction object, representing a ConnectionHolder.
	 * Used as transaction object by DataSourceTransactionManager.
	 */
	private static class DataSourceTransactionObject extends JdbcTransactionObjectSupport {

		private boolean newConnectionHolder;

		private boolean mustRestoreAutoCommit;

		public void setConnectionHolder(@Nullable ConnectionHolder connectionHolder, boolean newConnectionHolder) {
			super.setConnectionHolder(connectionHolder);
			this.newConnectionHolder = newConnectionHolder;
		}

		public boolean isNewConnectionHolder() {
			return this.newConnectionHolder;
		}

		public void setMustRestoreAutoCommit(boolean mustRestoreAutoCommit) {
			this.mustRestoreAutoCommit = mustRestoreAutoCommit;
		}

		public boolean isMustRestoreAutoCommit() {
			return this.mustRestoreAutoCommit;
		}

		public void setRollbackOnly() {
			getConnectionHolder().setRollbackOnly();
		}

		@Override
		public boolean isRollbackOnly() {
			return getConnectionHolder().isRollbackOnly();
		}

		@Override
		public void flush() {
			if (TransactionSynchronizationManager.isSynchronizationActive()) {
				TransactionSynchronizationUtils.triggerFlush();
			}
		}
	}

}
