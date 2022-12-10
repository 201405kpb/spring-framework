package com.kpb.spring.tx;

import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;

public class BookDao {

	org.slf4j.Logger logger =  LoggerFactory.getLogger(BookDao.class);

	private JdbcTemplate jdbcTemplate;

	public JdbcTemplate getJdbcTemplate() {
		return jdbcTemplate;
	}

	public void setJdbcTemplate(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public void add(Book book) {

		String addSql = "insert into Book(name, author, price) values(?,?,?)";
		Object[] args = {book.getName(), book.getAuthor(), book.getPrice()};
		int update = jdbcTemplate.update(addSql, args);
		logger.info(String.format("插入%s条数据", update));
	}


	public Book getBookById(int id) {
		String selectSql = "select * from book  where id=?";
		Object[] args = {id};
		return jdbcTemplate.queryForObject(selectSql, new BeanPropertyRowMapper<>(Book.class), args);
	}
}
