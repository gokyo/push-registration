Register for Push Registration
----
  Register device details for Push Notification.

* **URL**

  `/push/register`

* **Method:**

  `POST`

    Example JSON post payload for registration.

```json
{
  "token": "some-token"
}
```


*  **URL Params**

   **None:**
 
* **Success Response:**
  * **Code:** 200 <br />

  * **Code:** 201 <br />

* **Error Response:**

  * **Code:** 400 BADREQUEST <br />
    **Content:** `{"code":"BADREQUEST","message":"Bad Request"}`

  * **Code:** 401 UNAUTHORIZED <br />
    **Content:** `{"code":"UNAUTHORIZED","message":"Bearer token is missing or not authorized for access"}`

  * **Code:** 403 FORBIDDEN <br />
    **Content:** `{"code":"FORBIDDEN","message":"Access denied"}`

  * **Code:** 406 NOT ACCEPTABLE <br />
    **Content:** `{"code":"ACCEPT_HEADER_INVALID","message":"The accept header is missing or invalid"}`

  OR when the details cannot be resolved.

  * **Code:** 500 INTERNAL_SERVER_ERROR <br />


