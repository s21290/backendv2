package com.gary.backendv2.security.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.gary.backendv2.exception.HttpException;
import com.gary.backendv2.model.dto.request.users.*;
import com.gary.backendv2.model.enums.EmployeeType;
import com.gary.backendv2.model.security.ResetPasswordToken;
import com.gary.backendv2.model.security.ResetPasswordTokenRepository;
import com.gary.backendv2.model.security.Role;
import com.gary.backendv2.model.security.UserPrincipal;
import com.gary.backendv2.model.dto.response.JwtResponse;
import com.gary.backendv2.model.enums.RoleName;
import com.gary.backendv2.model.users.*;
import com.gary.backendv2.model.users.employees.Dispatcher;
import com.gary.backendv2.model.users.employees.Medic;
import com.gary.backendv2.model.users.employees.WorkSchedule;
import com.gary.backendv2.repository.MedicalInfoRepository;
import com.gary.backendv2.repository.RoleRepository;
import com.gary.backendv2.repository.UserRepository;
import com.gary.backendv2.repository.WorkScheduleRepository;
import com.gary.backendv2.utils.JwtUtils;
import com.gary.backendv2.utils.Utils;
import lombok.AllArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.core.env.Environment;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.HttpStatus;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.FileCopyUtils;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@AllArgsConstructor
public class AuthService {
    private UserRepository userRepository;
    private RoleRepository roleRepository;
    private MedicalInfoRepository medicalInfoRepository;

    private PasswordEncoder passwordEncoder;
    private AuthenticationManager authenticationManager;
    private  WorkScheduleRepository workScheduleRepository;
    private JwtUtils jwtUtils;


    private JavaMailSender emailSender;

    public JwtResponse authenticateUser(LoginRequest loginRequest) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(loginRequest.getEmail(), loginRequest.getPassword())
        );

        SecurityContextHolder.getContext().setAuthentication(authentication);
        String jwt = jwtUtils.generateJwt(authentication);
        UserPrincipal userPrincipal = (UserPrincipal) authentication.getPrincipal();

        Set<String> roles = userPrincipal
                .getAuthorities()
                .stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());

        return JwtResponse
                .builder()
                .email(userPrincipal.getUsername())
                .token(jwt)
                .roles(roles)
                .userId(userPrincipal.getUserData().getUserId())
                .type("Bearer ")
                .build();
    }

    public void registerUser(SignupRequest signupRequest) throws HttpException {
        if (userRepository.existsByEmail(signupRequest.getEmail())) {
            throw new HttpException(HttpStatus.BAD_REQUEST, "Email already in use!");
        }

        MedicalInfo mi = new MedicalInfo();
        mi = medicalInfoRepository.save(mi);

        User user = User.builder()
                .firstName(signupRequest.getFirstName())
                .lastName(signupRequest.getLastName())
                .birthDate(signupRequest.getBirthDate())
                .password(passwordEncoder.encode(signupRequest.getPassword()))
                .phoneNumber(signupRequest.getPhoneNumber())
                .email(signupRequest.getEmail())
                .userId(null)
                .medicalInfo(mi)
                .build();


        Role userRole = Optional.ofNullable(roleRepository.findByName(RoleName.USER.getPrefixedName())).orElseThrow(() -> new RuntimeException("Role " + RoleName.USER + " not found!"));

        user.setRoles(Set.of(userRole));
        userRepository.save(user);
    }

    public void changePassword(Authentication authentication, ChangePasswordRequest passwordRequest) {
        User user = getLoggedUserFromAuthentication(authentication);
        if (user == null) {
            throw new HttpException(HttpStatus.FORBIDDEN);
        }

        String currentPassword = user.getPassword();

        if (currentPassword.equals(passwordEncoder.encode(passwordRequest.getNewPassword()))) {
            throw new HttpException(HttpStatus.BAD_REQUEST, "There was an error processing your request, try again");
        }

        user.setPassword(passwordEncoder.encode(passwordRequest.getNewPassword()));

        userRepository.save(user);
    }

    ResetPasswordTokenRepository resetPasswordTokenRepository;
    private Environment environment;
    public void sendPasswordResetToken(PasswordResetTokenRequest passwordResetTokenRequest) {
        String passwordResetFeatureFlag = environment.getProperty("gary.feature_flags.password_reset");

        if (passwordResetFeatureFlag == null || passwordResetFeatureFlag.equals("false")) {
            throw new HttpException(HttpStatus.INTERNAL_SERVER_ERROR, "Password reset feature flag is set to false");
        }

         Optional<User> userOptional = userRepository.findByEmail(passwordResetTokenRequest.getEmail());
         if (userOptional.isEmpty()) {
             throw new HttpException(HttpStatus.NOT_FOUND, "User not found");
         }
         User user = userOptional.get();

        ResetPasswordToken token = Utils.generatePasswordResetTokenForUser(user);
        token = resetPasswordTokenRepository.save(token);

        SimpleMailMessage email = new SimpleMailMessage();
        email.setSubject("Gary: Password Reset");
        email.setFrom(environment.getProperty("spring.mail.username"));
        email.setText("Reset password for : " + user.getEmail() + "\r\n" + "Reset token: " + token.getToken() + "\r\n" + "Please provide this token in password reset form");
        email.setTo(user.getEmail());

        emailSender.send(email);
    }

    public void resetPassword(String token, String newPassword) {
        Optional<ResetPasswordToken> tokenOptional = resetPasswordTokenRepository.findByTokenOrderByCreatedAtDesc(token);
        if (tokenOptional.isEmpty()) {
            throw new HttpException(HttpStatus.BAD_REQUEST);
        }

        ResetPasswordToken t = tokenOptional.get();
        if (!t.isValid()) {
            throw new HttpException(HttpStatus.BAD_REQUEST, "Invalid token, probably been used already. Try generate a new one");
        }

        User user = t.getUser();

        user.setPassword(passwordEncoder.encode(newPassword));

        t.setValid(false);

        resetPasswordTokenRepository.save(t);
        userRepository.save(user);
    }

    public void registerEmployee(EmployeeType employeeType, SignupRequest signupRequest) {
        if (userRepository.existsByEmail(signupRequest.getEmail())) {
            throw new HttpException(HttpStatus.BAD_REQUEST, "Email already in use!");
        }

        switch (employeeType) {
            case DISPATCHER -> registerDispatcher(signupRequest);
            case MEDIC -> registerMedic(signupRequest);
        }
    }

    @SneakyThrows
    private void registerDispatcher(SignupRequest signupRequest) {
        RegisterEmployeeRequest employeeRequest = (RegisterEmployeeRequest) signupRequest;

        WorkSchedule workSchedule = getWorkScheduleFromRequest(employeeRequest);

        workSchedule = workScheduleRepository.save(workSchedule);

        Dispatcher dispatcher = new Dispatcher();
        dispatcher.setFirstName(employeeRequest.getFirstName());
        dispatcher.setLastName(employeeRequest.getLastName());
        dispatcher.setEmail(employeeRequest.getEmail());
        dispatcher.setPhoneNumber(employeeRequest.getPhoneNumber());
        dispatcher.setWorkSchedule(workSchedule);
        dispatcher.setBirthDate(employeeRequest.getBirthDate());
        dispatcher.setPassword(passwordEncoder.encode(employeeRequest.getPassword()));


        Role userRole = Optional.ofNullable(
                roleRepository.findByName(RoleName.DISPATCHER.getPrefixedName())
        ).orElseThrow(() -> new RuntimeException("Role " + RoleName.DISPATCHER + " not found!"));

        dispatcher.setRoles(Set.of(userRole));
        userRepository.save(dispatcher);
    }

    @SneakyThrows
    private void registerMedic(SignupRequest signupRequest) {
        RegisterEmployeeRequest employeeRequest = (RegisterEmployeeRequest) signupRequest;

        WorkSchedule workSchedule = getWorkScheduleFromRequest(employeeRequest);

        workSchedule = workScheduleRepository.save(workSchedule);

        Medic medic = new Medic();
        medic.setFirstName(employeeRequest.getFirstName());
        medic.setLastName(employeeRequest.getLastName());
        medic.setEmail(employeeRequest.getEmail());
        medic.setPhoneNumber(employeeRequest.getPhoneNumber());
        medic.setWorkSchedule(workSchedule);
        medic.setBirthDate(employeeRequest.getBirthDate());
        medic.setPassword(passwordEncoder.encode(employeeRequest.getPassword()));

        Role userRole = Optional.ofNullable(
                roleRepository.findByName(RoleName.MEDIC.getPrefixedName())
        ).orElseThrow(() -> new RuntimeException("Role " + RoleName.MEDIC + " not found!"));

        medic.setRoles(Set.of(userRole));
        userRepository.save(medic);
    }

    private WorkSchedule getWorkScheduleFromRequest(RegisterEmployeeRequest employeeRequest) throws JsonProcessingException {
        WorkSchedule workSchedule = new WorkSchedule();

        if (employeeRequest.getWorkSchedule().isEmpty()) {
            workSchedule.setSchedule(getDefaultWorkSchedule());
            workSchedule.setCreatedAt(LocalDateTime.now());
        } else {
            String json = Utils.POJOtoJsonString(employeeRequest.getWorkSchedule());

            workSchedule.setSchedule(json);
            workSchedule.setCreatedAt(LocalDateTime.now());
        }

        return workSchedule;
    }

    @SneakyThrows
    private String getDefaultWorkSchedule() {
        ResourceLoader resourceLoader = new DefaultResourceLoader();
        Resource resource = resourceLoader.getResource("classpath:default-work-schedule.json");

        String json;
        try (Reader reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
            json = FileCopyUtils.copyToString(reader);
        } catch (IOException e) {
            throw new HttpException(HttpStatus.INTERNAL_SERVER_ERROR, "Error reading default work schedule");
        }

        return json;
    }
    public User getLoggedUserFromAuthentication(Authentication authentication) {
        if (authentication == null) {
            return null;
        }

        UserPrincipal loggedPrincipal = (UserPrincipal) authentication.getPrincipal();

        return userRepository.getByEmail(loggedPrincipal.getUsername());
    }
}
