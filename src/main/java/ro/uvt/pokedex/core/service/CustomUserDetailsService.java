package ro.uvt.pokedex.core.service;

import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.repository.UserRepository;
import ro.uvt.pokedex.core.model.user.User;

@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    @NonNull
    @Override
    public UserDetails loadUserByUsername(@NonNull String email) throws UsernameNotFoundException {
        return userRepository.findById(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found with email: " + email));
    }
}
