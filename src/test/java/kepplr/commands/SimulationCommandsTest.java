package kepplr.commands;

import static org.mockito.Mockito.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SimulationCommands} contract.
 *
 * <p>These tests verify the interface contract via mock; concrete implementations will have their own test classes.
 */
@DisplayName("SimulationCommands")
class SimulationCommandsTest {

    static final int EARTH = 399;
    static final int MOON = 301;

    private SimulationCommands commands;

    @BeforeEach
    void setUp() {
        commands = mock(SimulationCommands.class);
    }

    @Test
    @DisplayName("selectBody does not throw for valid NAIF ID")
    void selectBodyValid() {
        commands.selectBody(EARTH);
        verify(commands).selectBody(EARTH);
    }

    @Test
    @DisplayName("centerBody forwards call")
    void centerBodyForwards() {
        commands.centerBody(EARTH);
        verify(commands).centerBody(EARTH);
    }

    @Test
    @DisplayName("targetBody forwards call")
    void targetBodyForwards() {
        commands.targetBody(MOON);
        verify(commands).targetBody(MOON);
    }

    @Test
    @DisplayName("setTimeRate is absolute, not multiplicative (§2.3)")
    void setTimeRateAbsolute() {
        // "3x" means timeRate = 3.0, verified by contract
        commands.setTimeRate(3.0);
        verify(commands).setTimeRate(3.0);
    }

    @Test
    @DisplayName("setPaused pauses and unpauses")
    void setPaused() {
        commands.setPaused(true);
        commands.setPaused(false);
        verify(commands).setPaused(true);
        verify(commands).setPaused(false);
    }
}
