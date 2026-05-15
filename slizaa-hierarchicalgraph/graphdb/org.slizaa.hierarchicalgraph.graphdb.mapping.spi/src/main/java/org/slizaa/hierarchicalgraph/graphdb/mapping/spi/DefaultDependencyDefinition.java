package org.slizaa.hierarchicalgraph.graphdb.mapping.spi;

/**
 * <p>
 * </p>
 *
 * @author Gerd W&uuml;therich (gerd@gerd-wuetherich.de)
 */
public class DefaultDependencyDefinition implements IDependencyDefinition {

  /** - */
  public long   _idStart;

  /** - */
  public long   _idTarget;

  /** - */
  public long   _idRel;

  /** - */
  public String _type;

  /**
   * <p>
   * Creates a new instance of type {@link AbstractDependency}.
   * </p>
   *
   * @param idStart
   * @param idTarget
   * @param idRel
   * @param type
   */
  public DefaultDependencyDefinition(long idStart, long idTarget, long idRel, String type) {
    this._idStart = idStart;
    this._idTarget = idTarget;
    this._idRel = idRel;
    this._type = type;
  }

  @Override
  public long getIdStart() {
    return this._idStart;
  }

  @Override
  public long getIdTarget() {
    return this._idTarget;
  }

  @Override
  public long getIdRel() {
    return this._idRel;
  }

  @Override
  public String getType() {
    return this._type;
  }

  @Override
  public String toString() {
    return "DefaultDependencyDefinition [_idStart=" + this._idStart + ", _idTarget=" + this._idTarget + ", _idRel="
        + this._idRel + ", _type=" + this._type + "]";
  }
}