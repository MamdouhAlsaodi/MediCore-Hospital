package com.mamtrex.hospital.billing; import org.springframework.data.jpa.repository.JpaRepository; import java.util.UUID; public interface InvoiceRepository extends JpaRepository<Invoice,UUID>{}
