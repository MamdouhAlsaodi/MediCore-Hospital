package com.mamtrex.hospital.pharmacy; import org.springframework.data.jpa.repository.JpaRepository; import java.util.UUID; public interface DrugRepository extends JpaRepository<Drug,UUID>{}
