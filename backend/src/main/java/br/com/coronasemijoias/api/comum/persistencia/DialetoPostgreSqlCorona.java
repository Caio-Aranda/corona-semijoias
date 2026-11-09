package br.com.coronasemijoias.api.comum.persistencia;

import org.hibernate.dialect.PostgreSQLDialect;
import org.hibernate.engine.jdbc.dialect.spi.DialectResolutionInfo;

/**
 * Dialeto do PostgreSQL com uma única diferença: o UPDATE não usa {@code RETURNING}.
 *
 * <p>Motivo (provado em {@code SuperclassesJpaTest}): os campos de controle são preenchidos pelo
 * gatilho e mapeados com {@code @Generated}. Com {@code RETURNING} no UPDATE, o Hibernate 7.4 relê
 * esses campos na mesma instrução, mas, quando outra transação já mudou a versão, o
 * {@code UPDATE ... WHERE version = ?} não acha a linha e o Hibernate responde com um erro genérico
 * ("no natively generated values") em vez do conflito de edição ({@code OptimisticLockException}).
 * Sem {@code RETURNING} no UPDATE, ele confere quantas linhas mudaram (zero vira conflito) e só
 * depois relê os campos com um SELECT. A inserção continua com {@code RETURNING}.
 *
 * <p>Se uma versão futura do Hibernate passar a dar o conflito certo, este dialeto pode sair; o
 * teste avisa.
 */
public class DialetoPostgreSqlCorona extends PostgreSQLDialect {

    public DialetoPostgreSqlCorona() {
        super();
    }

    public DialetoPostgreSqlCorona(DialectResolutionInfo info) {
        super(info);
    }

    @Override
    public boolean supportsUpdateReturning() {
        return false;
    }
}
