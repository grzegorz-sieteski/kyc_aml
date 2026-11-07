/**
 * Shared kernel: the handful of types every business module is allowed to depend on.
 *
 * <p>Keep this package tiny. Everything added here becomes a dependency of every module and
 * erodes the point of package-by-feature. Nothing framework-specific belongs here.
 */
@NullMarked
package com.gs5.kycaml.shared.kernel;

import org.jspecify.annotations.NullMarked;
