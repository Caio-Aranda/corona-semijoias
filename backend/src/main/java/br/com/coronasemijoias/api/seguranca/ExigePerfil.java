package br.com.coronasemijoias.api.seguranca;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Set;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;

/**
 * Perfis que podem usar a rota (ADR-0004, item 9). Vai no controlador ou no método; a do método vale
 * sobre a do controlador e só pode restringir. <strong>Rota sem esta anotação é negada</strong> (fora da
 * lista fechada de {@link RotasPublicas}), e o teste {@code TodasAsRotasTest} reprova o build.
 *
 * <p>O grupo do prefixo também vale: em {@code /api/dona/**} só entram os perfis de
 * {@code corona.seguranca.grupos.dona}, mesmo que a anotação liste outro. Perfil novo: uma linha numa
 * migração ({@code perfis}), a anotação nas rotas que ele usa e, se for o caso, o valor no grupo.
 *
 * <pre>{@code
 * @ExigePerfil("ADMIN")
 * @RestController
 * @RequestMapping("/api/dona/produtos")
 * class ProdutosDaDonaController { ... }
 * }</pre>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface ExigePerfil {

    /** Códigos da tabela {@code perfis}, por exemplo {@code "ADMIN"}. */
    String[] value();

    /** Leitura da anotação de uma rota. */
    final class Leitura {

        private Leitura() {
        }

        /** A anotação do método ou, se ele não tiver, a do controlador; {@code null} se nenhum declarar. */
        public static ExigePerfil de(HandlerMethod metodo) {
            ExigePerfil doMetodo = AnnotatedElementUtils.findMergedAnnotation(metodo.getMethod(), ExigePerfil.class);
            if (doMetodo != null) {
                return doMetodo;
            }
            return AnnotatedElementUtils.findMergedAnnotation(metodo.getBeanType(), ExigePerfil.class);
        }

        /** Só a anotação do controlador ({@code null} se não houver). */
        public static ExigePerfil doControlador(HandlerMethod metodo) {
            return AnnotatedElementUtils.findMergedAnnotation(metodo.getBeanType(), ExigePerfil.class);
        }

        public static Set<String> perfis(ExigePerfil anotacao) {
            return anotacao == null ? Set.of() : Set.of(anotacao.value());
        }
    }
}
