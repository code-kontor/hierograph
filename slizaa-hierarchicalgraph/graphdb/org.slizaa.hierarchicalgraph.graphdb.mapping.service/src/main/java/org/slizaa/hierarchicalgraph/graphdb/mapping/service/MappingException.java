package org.slizaa.hierarchicalgraph.graphdb.mapping.service;

/**
 * <p>
 * </p>
 *
 * @author Gerd W&uuml;therich (gerd@gerd-wuetherich.de)
 */
@SuppressWarnings("serial")
public class MappingException extends RuntimeException {

  /**
   * <p>
   * Creates a new instance of type {@link MappingException}.
   * </p>
   *
   */
  public MappingException() {
    super();
  }

  /**
   * <p>
   * Creates a new instance of type {@link MappingException}.
   * </p>
   *
   * @param message
   * @param cause
   */
  public MappingException(String message, Throwable cause) {
    super(message, cause);
  }

  /**
   * <p>
   * Creates a new instance of type {@link MappingException}.
   * </p>
   *
   * @param message
   */
  public MappingException(String message) {
    super(message);
  }

  /**
   * <p>
   * Creates a new instance of type {@link MappingException}.
   * </p>
   *
   * @param cause
   */
  public MappingException(Throwable cause) {
    super(cause);
  }
}
