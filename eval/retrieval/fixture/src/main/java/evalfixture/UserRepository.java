package evalfixture;

import java.util.Optional;

public interface UserRepository {
    Optional<String> findByEmail(String email);
}
