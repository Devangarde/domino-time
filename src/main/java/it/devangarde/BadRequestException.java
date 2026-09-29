package it.devangarde;

public class BadRequestException extends Exception {

	private static final long serialVersionUID = 1L;
	
	public BadRequestException() {
		super("invalidRequest");
	}

	public BadRequestException(String message) {
		super(message);
	}
}
