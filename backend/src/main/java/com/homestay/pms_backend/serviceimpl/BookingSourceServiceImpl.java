package com.homestay.pms_backend.serviceimpl;

import com.homestay.pms_backend.dto.request.BookingSourceUpdateRequest;
import com.homestay.pms_backend.dto.response.BookingSourceResponse;
import com.homestay.pms_backend.entity.BookingSource;
import com.homestay.pms_backend.exception.ResourceNotFoundException;
import com.homestay.pms_backend.mapper.BookingSourceMapper;
import com.homestay.pms_backend.repository.BookingSourceRepository;
import com.homestay.pms_backend.service.BookingSourceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class BookingSourceServiceImpl implements BookingSourceService {

    private final BookingSourceRepository bookingSourceRepository;
    private final BookingSourceMapper bookingSourceMapper;

    @Override
    @Transactional(readOnly = true)
    public BookingSourceResponse getBookingSourceById(UUID id) {

        BookingSource bookingSource = bookingSourceRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Booking source not found with id: " + id));

        return bookingSourceMapper.toResponse(bookingSource);
    }

    @Override
    @Transactional(readOnly = true)
    public List<BookingSourceResponse> getAllBookingSources() {

        return bookingSourceRepository.findAll()
                .stream()
                .map(bookingSourceMapper::toResponse)
                .toList();
    }

    @Override
    public BookingSourceResponse updateBookingSource(
            UUID id,
            BookingSourceUpdateRequest request) {

        BookingSource bookingSource = bookingSourceRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Booking source not found with id: " + id));

        bookingSourceMapper.updateEntity(bookingSource, request);

        BookingSource updatedBookingSource =
                bookingSourceRepository.saveAndFlush(bookingSource);

        return bookingSourceMapper.toResponse(updatedBookingSource);
    }

    @Override
    public void deactivateBookingSource(UUID id) {

        BookingSource bookingSource = bookingSourceRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Booking source not found with id: " + id));

        bookingSource.setActive(false);

        bookingSourceRepository.saveAndFlush(bookingSource);
    }

    @Override
    public void activateBookingSource(UUID id) {

        BookingSource bookingSource = bookingSourceRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Booking source not found with id: " + id));

        bookingSource.setActive(true);

        bookingSourceRepository.saveAndFlush(bookingSource);
    }
}