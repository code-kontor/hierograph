package org.hg.fixture.locations.lib.audit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation applied to ReportController (ANNOTATED_BY -> OF_TYPE edge). The
 * java.lang.annotation meta-annotations are excluded from the virtual-external
 * model, so they produce no extra target nodes.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Auditable {
}
