package com.vehiclemanagement.web;

import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

import java.lang.reflect.Type;

/**
 * Keeps the JSON body a request arrived with, for the length of that request, so
 * {@link GlobalExceptionHandler} can quote the value a service refused. Services throw a field
 * name and a reason; the value is already here.
 */
@ControllerAdvice
public class RequestBodyMemory extends RequestBodyAdviceAdapter {

    static final String ATTRIBUTE = RequestBodyMemory.class.getName() + ".body";

    @Override
    public boolean supports(MethodParameter parameter, Type targetType,
                            Class<? extends HttpMessageConverter<?>> converter) {
        return true;
    }

    @Override
    public Object afterBodyRead(Object body, HttpInputMessage input, MethodParameter parameter,
                                Type targetType,
                                Class<? extends HttpMessageConverter<?>> converter) {
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        if (request != null) {
            request.setAttribute(ATTRIBUTE, body, RequestAttributes.SCOPE_REQUEST);
        }
        return body;
    }
}
