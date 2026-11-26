package dev.jefe.mailsorter.infra.memory.inMemory;

import dev.jefe.mailsorter.infra.persistence.memory.InMemoryConnectionStore;
import dev.jefe.mailsorter.testsupport.FakeMailProvider;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryConnectionStoreTest {

    @Test
    void savingANewProviderClosesTheOldOne() {
        var store = new InMemoryConnectionStore();
        var first = new FakeMailProvider();
        var second = new FakeMailProvider();

        store.save(first);
        store.save(second);

        assertThat(first.closeCalls.get()).isEqualTo(1);
        assertThat(second.closeCalls.get()).isZero();
        assertThat(store.current()).containsSame(second);
    }

    @Test
    void savingTheSameProviderAgainDoesNotCloseIt() {
        var store = new InMemoryConnectionStore();
        var provider = new FakeMailProvider();

        store.save(provider);
        store.save(provider);

        assertThat(provider.isClosed()).isFalse();
    }

    @Test
    void aFailingCloseDoesNotBreakTheSwitch() {
        var store = new InMemoryConnectionStore();
        var first = new FakeMailProvider();
        first.closeFailure = new RuntimeException("boom");
        var second = new FakeMailProvider();

        store.save(first);
        store.save(second);                       // must not throw

        assertThat(store.current()).containsSame(second);
    }

}
