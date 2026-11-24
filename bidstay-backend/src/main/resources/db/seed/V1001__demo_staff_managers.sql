UPDATE hotel_staff s
SET can_manage_staff = TRUE
FROM users u
WHERE u.id = s.user_id AND u.phone IN ('9999900003', '9999900005')
  AND u.role = 'HOTEL_STAFF';
