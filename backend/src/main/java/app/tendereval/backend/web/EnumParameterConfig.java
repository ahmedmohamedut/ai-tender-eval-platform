package app.tendereval.backend.web;

import java.lang.reflect.Method;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.convert.converter.ConverterFactory;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.reactive.config.WebFluxConfigurer;

/**
 * Query parameters carry the contract's lowercase values ({@code role=observer}). The generated
 * enums map them with a static {@code fromValue}, which Spring's default string-to-enum conversion
 * (by constant name) does not use.
 */
@Configuration
public class EnumParameterConfig implements WebFluxConfigurer {

  @Override
  public void addFormatters(FormatterRegistry registry) {
    registry.addConverterFactory(new ValueEnumConverterFactory());
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  static final class ValueEnumConverterFactory implements ConverterFactory<String, Enum> {

    @Override
    public <T extends Enum> Converter<String, T> getConverter(Class<T> targetType) {
      Method fromValue;
      try {
        fromValue = targetType.getMethod("fromValue", String.class);
      } catch (NoSuchMethodException e) {
        return source -> (T) Enum.valueOf(targetType, source.trim().toUpperCase());
      }
      return source -> {
        try {
          return (T) fromValue.invoke(null, source.trim());
        } catch (java.lang.reflect.InvocationTargetException e) {
          throw new IllegalArgumentException("Unexpected value '" + source + "'", e.getCause());
        } catch (IllegalAccessException e) {
          throw new IllegalStateException(e);
        }
      };
    }
  }
}
