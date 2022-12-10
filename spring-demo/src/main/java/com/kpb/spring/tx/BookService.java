package com.kpb.spring.tx;

public class BookService {

	private BookDao bookDao;

	public BookDao getBookDao() {
		return bookDao;
	}

	public void setBookDao(BookDao bookDao) {
		this.bookDao = bookDao;
	}

	public void add(Book book) {
		bookDao.add(book);
	}

	public Book getBookById(int id) {
		return bookDao.getBookById(id);
	}
}
