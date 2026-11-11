package br.com.coronasemijoias.api.documentacao;

import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.core.jackson.AbstractModelConverter;
import io.swagger.v3.core.jackson.ModelResolver;
import io.swagger.v3.core.jackson.TypeNameResolver;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.RegexPatternTypeFilter;
import java.util.regex.Pattern;
import java.lang.reflect.Field;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Nomes únicos para os esquemas do OpenAPI (auditoria de 11/11/2026, achado M1). O springdoc usa só o nome simples da
 * classe, então dois records {@code Linha} (de classes diferentes) viravam um esquema só e os clientes gerados saíam
 * com o tipo errado. Aqui, quando o nome simples de uma classe aninhada se repete no código, o esquema ganha o nome da
 * classe que a contém na frente ({@code ConsultasDaConferenciaLinha}); os nomes que nunca se repetiram não mudam.
 * Uma anotação {@code @Schema(name = …)} continua mandando.
 *
 * <p>Só liga quando o OpenAPI está ligado (perfil dev e testes que o pedem).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "springdoc.api-docs.enabled", havingValue = "true")
public class NomesUnicosDosEsquemas {

    static final String PACOTE = "br.com.coronasemijoias.api";

    /**
     * Troca o resolvedor de nomes dos conversores de modelo do swagger depois que o springdoc montou os seus. O campo
     * {@code _typeNameResolver} é {@code final} e sem método para trocá-lo (a classe só aceita um resolvedor novo na
     * construção, o que tiraria do caminho os tipos que o springdoc substitui, como dinheiro e cotação).
     */
    @Bean
    SmartInitializingSingleton trocarResolvedorDeNomes() {
        return () -> {
            Resolvedor nomes = new Resolvedor(nomesRepetidos());
            for (boolean openapi31 : new boolean[] {false, true}) {
                for (ModelConverter c : ModelConverters.getInstance(openapi31).getConverters()) {
                    if (c instanceof ModelResolver) {
                        try {
                            Field campo = AbstractModelConverter.class.getDeclaredField("_typeNameResolver");
                            campo.setAccessible(true);
                            campo.set(c, nomes);
                        } catch (ReflectiveOperationException e) {
                            throw new IllegalStateException("Não consegui trocar o resolvedor de nomes do OpenAPI.", e);
                        }
                    }
                }
            }
        };
    }

    /** Nomes simples que aparecem em mais de uma classe do projeto (a aninhada leva o nome de quem a contém). */
    static Set<String> nomesRepetidos() {
        Map<String, Integer> contagem = new HashMap<>();
        for (Class<?> c : classesDoProjeto()) {
            if (!c.isAnonymousClass() && !c.isLocalClass() && !c.isSynthetic()) {
                contagem.merge(c.getSimpleName(), 1, Integer::sum);
            }
        }
        Set<String> repetidos = new HashSet<>();
        contagem.forEach((nome, n) -> {
            if (n > 1) {
                repetidos.add(nome);
            }
        });
        return repetidos;
    }

    /** Todas as classes do projeto, aninhadas inclusive. */
    static java.util.List<Class<?>> classesDoProjeto() {
        ClassPathScanningCandidateComponentProvider varredor = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(AnnotatedBeanDefinition definicao) {
                return true;
            }
        };
        varredor.addIncludeFilter(new RegexPatternTypeFilter(Pattern.compile(".*")));
        java.util.List<Class<?>> classes = new java.util.ArrayList<>();
        for (BeanDefinition d : varredor.findCandidateComponents(PACOTE)) {
            try {
                classes.add(Class.forName(d.getBeanClassName(), false, NomesUnicosDosEsquemas.class.getClassLoader()));
            } catch (ClassNotFoundException | LinkageError e) {
                // classe que não carrega fora do contexto não vira esquema
            }
        }
        return classes;
    }

    static String nomeDoEsquema(Class<?> tipo, Set<String> repetidos) {
        Schema anotacao = tipo.getAnnotation(Schema.class);
        if (anotacao != null && !anotacao.name().isEmpty()) {
            return anotacao.name();
        }
        Class<?> fora = tipo.getEnclosingClass();
        if (fora == null || !repetidos.contains(tipo.getSimpleName())) {
            return tipo.getSimpleName();
        }
        return nomeDoEsquema(fora, repetidos).concat(tipo.getSimpleName());
    }

    private static final class Resolvedor extends TypeNameResolver {
        private final Set<String> repetidos;

        Resolvedor(Set<String> repetidos) {
            this.repetidos = repetidos;
        }

        @Override
        protected String nameForClass(Class<?> cls, Set<Options> options) {
            if (options.contains(Options.SKIP_API_MODEL)) {
                return super.nameForClass(cls, options);
            }
            return nomeDoEsquema(cls, repetidos);
        }
    }
}
