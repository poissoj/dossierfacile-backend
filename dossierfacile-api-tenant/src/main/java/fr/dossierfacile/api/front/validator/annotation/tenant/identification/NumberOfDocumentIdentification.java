package fr.dossierfacile.api.front.validator.annotation.tenant.identification;

import fr.dossierfacile.api.front.validator.tenant.identification.NumberOfDocumentIdentificationValidator;
import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)

@Constraint(
        validatedBy = {NumberOfDocumentIdentificationValidator.class}
)

public @interface NumberOfDocumentIdentification {
    String message() default "number of document must be between {min} and {max}";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    int min() default 1;

    int max() default 2147483647;
}
