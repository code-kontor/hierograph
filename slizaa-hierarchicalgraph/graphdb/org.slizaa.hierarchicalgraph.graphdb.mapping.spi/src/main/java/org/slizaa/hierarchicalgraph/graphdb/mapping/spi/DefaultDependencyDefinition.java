package org.slizaa.hierarchicalgraph.graphdb.mapping.spi;

public class DefaultDependencyDefinition implements IDependencyDefinition {

  public long   _idStart;

  public long   _idTarget;

  public long   _idRel;

  public String _type;

  public int _weight;

  public int _attributesBitmap;

  public DefaultDependencyDefinition(long idStart, long idTarget, long idRel, String type, int weight) {
    this(idStart, idTarget, idRel, type, weight, 0);
  }

  public DefaultDependencyDefinition(long idStart, long idTarget, long idRel, String type, int weight, int attributesBitmap) {
    this._idStart = idStart;
    this._idTarget = idTarget;
    this._idRel = idRel;
    this._type = type;
    this._weight = weight;
    this._attributesBitmap = attributesBitmap;
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
  public int getWeight() {
    return this._weight;
  }

  @Override
  public int getAttributesBitmap() {
    return this._attributesBitmap;
  }

  @Override
  public String toString() {
    return "DefaultDependencyDefinition [_idStart=" + this._idStart + ", _idTarget=" + this._idTarget + ", _idRel="
        + this._idRel + ", _type=" + this._type + "]";
  }
}