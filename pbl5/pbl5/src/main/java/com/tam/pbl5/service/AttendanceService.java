package com.tam.pbl5.service;

import com.tam.pbl5.dto.request.AttendanceCreateRequest;
import com.tam.pbl5.dto.request.StudentCheckinRequest;
import com.tam.pbl5.entity.*;
import com.tam.pbl5.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AttendanceService {

    private final AttendanceRepository attendanceRepository;
    private final ClassRepository classRepository;
    private final TeacherRepository teacherRepository;
    private final StudentRepository studentRepository;
    private final StudentClassRepository studentClassRepository;
    private final StudentAttendanceRepository studentAttendanceRepository;
    private final JwtService jwtService;

    // ==========================================
    // 1. GIÁO VIÊN TẠO BUỔI ĐIỂM DANH
    // ==========================================
    @Transactional
    public String createAttendanceSession(AttendanceCreateRequest request, String token) {
        if (token != null && token.startsWith("Bearer ")) token = token.substring(7);
        String username = jwtService.extractUsername(token);
        String role = jwtService.extractRole(token);

        if (!"ROLE_TEACHER".equalsIgnoreCase(role)) {
            throw new RuntimeException("Lỗi: Chỉ giáo viên mới được phép tạo buổi điểm danh!");
        }

        Teacher teacher = teacherRepository.findByUsername(username);
        if (teacher == null) throw new RuntimeException("Lỗi: Không tìm thấy hồ sơ giáo viên!");

        Clazz clazz = classRepository.findById(request.getClassId())
                .orElseThrow(() -> new RuntimeException("Lỗi: Lớp học không tồn tại!"));

        if (!clazz.getTeacherId().equals(teacher.getId())) {
            throw new RuntimeException("Lỗi: Bạn không có quyền tạo điểm danh cho lớp của giáo viên khác!");
        }

        Attendance attendance = new Attendance();
        attendance.setClassId(clazz.getId());
        attendance.setDatetime(request.getDatetime() != null ? request.getDatetime() : LocalDateTime.now());

        attendanceRepository.save(attendance);
        return "Đã tạo thành công buổi điểm danh cho lớp " + clazz.getName() + "!";
    }

    // ==========================================
    // 2. SINH VIÊN TỰ ĐIỂM DANH
    // ==========================================
    @Transactional
    public String studentCheckin(StudentCheckinRequest request, String token) {
        // 1. Giải mã Token
        if (token != null && token.startsWith("Bearer ")) token = token.substring(7);
        String username = jwtService.extractUsername(token);
        String role = jwtService.extractRole(token);

        // 2. Chỉ cho phép sinh viên
        if (!"ROLE_STUDENT".equalsIgnoreCase(role)) {
            throw new RuntimeException("Lỗi: Chỉ sinh viên mới được phép tự điểm danh!");
        }

        // 3. Tìm sinh viên dựa vào token
        Student student = studentRepository.findByUsername(username);
        if (student == null) throw new RuntimeException("Lỗi: Không tìm thấy hồ sơ sinh viên!");

        // 4. Tìm buổi điểm danh dựa vào ID gửi từ FE
        Attendance attendance = attendanceRepository.findById(request.getAttendanceId())
                .orElseThrow(() -> new RuntimeException("Lỗi: Buổi điểm danh không tồn tại!"));

        // 5. Kiểm tra sinh viên có thực sự đang học lớp này không (Phải có status = APPROVED)
        StudentClass studentClass = studentClassRepository.findByStudentIdAndClassId(student.getId(), attendance.getClassId());
        if (studentClass == null || !"APPROVED".equalsIgnoreCase(studentClass.getStatus())) {
            throw new RuntimeException("Lỗi: Bạn không phải là thành viên chính thức của lớp này nên không thể điểm danh!");
        }

        // 6. Kiểm tra chống spam điểm danh nhiều lần
        if (studentAttendanceRepository.existsByAttendanceIdAndStudentId(attendance.getId(), student.getId())) {
            throw new RuntimeException("Lỗi: Bạn đã điểm danh cho buổi học này rồi!");
        }

        // 7. Lưu bản ghi điểm danh
        StudentAttendance checkin = new StudentAttendance();
        checkin.setAttendanceId(attendance.getId()); // Lấy từ bước 4 (hoặc request)
        checkin.setStudentId(student.getId());       // Lấy từ bước 3 (Token)
        checkin.setCheckInTime(LocalDateTime.now()); // Chữ "I" viết hoa khớp với Entity của bạn

        studentAttendanceRepository.save(checkin);

        return "Điểm danh thành công!";
    }
    public List<Student> getAttendedStudents(Integer attendanceId, String token) {
        // 1. Tách và giải mã Token
        if (token != null && token.startsWith("Bearer ")) token = token.substring(7);
        String username = jwtService.extractUsername(token);
        String role = jwtService.extractRole(token);

        // 2. Tìm buổi điểm danh
        Attendance attendance = attendanceRepository.findById(attendanceId)
                .orElseThrow(() -> new RuntimeException("Lỗi: Buổi điểm danh không tồn tại!"));

        // 3. Phân quyền xem danh sách
        if ("ROLE_TEACHER".equalsIgnoreCase(role)) {
            // Nếu là Giáo viên: Phải là chủ nhiệm lớp này mới được xem
            Teacher teacher = teacherRepository.findByUsername(username);
            Clazz clazz = classRepository.findById(attendance.getClassId())
                    .orElseThrow(() -> new RuntimeException("Lỗi: Lớp học không tồn tại!"));
            if (!clazz.getTeacherId().equals(teacher.getId())) {
                throw new RuntimeException("Lỗi: Bạn không có quyền xem danh sách điểm danh của lớp khác!");
            }
        } else if ("ROLE_STUDENT".equalsIgnoreCase(role)) {
            // Nếu là Sinh viên: Phải đang học lớp này mới được xem
            Student student = studentRepository.findByUsername(username);
            StudentClass studentClass = studentClassRepository.findByStudentIdAndClassId(student.getId(), attendance.getClassId());
            if (studentClass == null || !"APPROVED".equalsIgnoreCase(studentClass.getStatus())) {
                throw new RuntimeException("Lỗi: Bạn không thuộc lớp này nên không được xem danh sách!");
            }
        }

        // 4. Lấy danh sách các bản ghi điểm danh của buổi học này
        List<StudentAttendance> attendedRecords = studentAttendanceRepository.findByAttendanceId(attendanceId);

        // 5. Trích xuất ra 1 list chỉ chứa ID của các sinh viên đã điểm danh
        List<Integer> studentIds = attendedRecords.stream()
                .map(StudentAttendance::getStudentId)
                .collect(Collectors.toList());

        // 6. Dùng list ID đó để lấy thông tin chi tiết của sinh viên từ bảng Student
        return studentRepository.findAllById(studentIds);
    }
    // ==========================================
    // 4. XEM DANH SÁCH SINH VIÊN VẮNG MẶT
    // ==========================================
    public List<Student> getAbsentStudents(Integer attendanceId, String token) {
        // 1. Tách và giải mã Token
        if (token != null && token.startsWith("Bearer ")) token = token.substring(7);
        String username = jwtService.extractUsername(token);
        String role = jwtService.extractRole(token);

        // 2. Tìm buổi điểm danh
        Attendance attendance = attendanceRepository.findById(attendanceId)
                .orElseThrow(() -> new RuntimeException("Lỗi: Buổi điểm danh không tồn tại!"));

        // 3. Phân quyền xem danh sách (Tương tự như hàm xem người đi học)
        if ("ROLE_TEACHER".equalsIgnoreCase(role)) {
            Teacher teacher = teacherRepository.findByUsername(username);
            Clazz clazz = classRepository.findById(attendance.getClassId())
                    .orElseThrow(() -> new RuntimeException("Lỗi: Lớp học không tồn tại!"));
            if (!clazz.getTeacherId().equals(teacher.getId())) {
                throw new RuntimeException("Lỗi: Bạn không có quyền xem danh sách vắng của lớp khác!");
            }
        } else if ("ROLE_STUDENT".equalsIgnoreCase(role)) {
            Student student = studentRepository.findByUsername(username);
            StudentClass studentClass = studentClassRepository.findByStudentIdAndClassId(student.getId(), attendance.getClassId());
            if (studentClass == null || !"APPROVED".equalsIgnoreCase(studentClass.getStatus())) {
                throw new RuntimeException("Lỗi: Bạn không thuộc lớp này nên không được xem danh sách!");
            }
        }

        // 4. LẤY DANH SÁCH TẤT CẢ SINH VIÊN CHÍNH THỨC TRONG LỚP (APPROVED)
        List<StudentClass> allStudentsInClass = studentClassRepository.findByClassIdAndStatus(attendance.getClassId(), "APPROVED");
        List<Integer> allStudentIds = allStudentsInClass.stream()
                .map(StudentClass::getStudentId)
                .collect(Collectors.toList());

        // 5. LẤY DANH SÁCH NHỮNG NGƯỜI ĐÃ ĐIỂM DANH
        List<StudentAttendance> attendedRecords = studentAttendanceRepository.findByAttendanceId(attendanceId);
        List<Integer> attendedStudentIds = attendedRecords.stream()
                .map(StudentAttendance::getStudentId)
                .collect(Collectors.toList());

        // 6. TÌM RA NHỮNG NGƯỜI VẮNG MẶT (Có trong danh sách lớp nhưng KHÔNG CÓ trong danh sách điểm danh)
        List<Integer> absentStudentIds = allStudentIds.stream()
                .filter(id -> !attendedStudentIds.contains(id))
                .collect(Collectors.toList());

        // 7. Trả về thông tin chi tiết của các sinh viên vắng mặt
        return studentRepository.findAllById(absentStudentIds);
    }
}