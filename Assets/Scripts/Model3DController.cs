using UnityEngine;
using UnityEngine.EventSystems;

/// <summary>
/// PhysicsRaycasterを経由して3Dモデル上でのドラッグ操作を取得し、
/// 画面切り替え（ScrollRect）と競合せずにモデルを360度回転させるクラス
/// </summary>
[RequireComponent(typeof(Collider))]
public class Model3DController : MonoBehaviour, IPointerDownHandler, IDragHandler, IPointerUpHandler
{
    [Header("回転感度設定")]
    [SerializeField] private float rotationSpeed = 0.4f;
    [SerializeField] private bool invertX = false;
    [SerializeField] private bool invertY = false;

    [Header("慣性設定")]
    [SerializeField] private bool useInertia = true;
    [SerializeField] private float damping = 5f;

    private Vector2 lastTouchPosition;
    private Vector2 rotationVelocity;
    private bool isDragging = false;
    private float pointerDownTime;
    private PetRobotAIController aiController;

    private void Start()
    {
        // 親や自身の階層から AIコントローラーを取得
        aiController = GetComponentInParent<PetRobotAIController>();
        if (aiController == null) aiController = GetComponent<PetRobotAIController>();
    }

    public void OnPointerDown(PointerEventData eventData)
    {
        isDragging = true;
        lastTouchPosition = eventData.position;
        rotationVelocity = Vector2.zero;
        pointerDownTime = Time.time;
    }

    public void OnDrag(PointerEventData eventData)
    {
        if (!isDragging) return;

        Vector2 currentTouchPosition = eventData.position;
        Vector2 delta = currentTouchPosition - lastTouchPosition;

        // ドラッグ量に応じた回転角の計算
        float rotX = delta.y * rotationSpeed * (invertY ? -1f : 1f);
        float rotY = -delta.x * rotationSpeed * (invertX ? -1f : 1f);

        // カメラ視野・標準座標系に合わせた回転（Y軸: World / X軸: Local）
        transform.Rotate(Vector3.up, rotY, Space.World);
        transform.Rotate(Vector3.right, rotX, Space.World);

        rotationVelocity = new Vector2(rotX, rotY);
        lastTouchPosition = currentTouchPosition;
    }

    public void OnPointerUp(PointerEventData eventData)
    {
        isDragging = false;

        // 短いタップ（0.2秒未満）かつ、ドラッグ量が少なければ「ナデナデ（タップ）」と判定
        if (Time.time - pointerDownTime < 0.2f && rotationVelocity.sqrMagnitude < 0.5f)
        {
            if (aiController != null)
            {
                aiController.OnPetReaction();
            }
        }
    }

    private void Update()
    {
        // ドラッグ解除後の減衰慣性回転
        if (!isDragging && useInertia && rotationVelocity.sqrMagnitude > 0.01f)
        {
            transform.Rotate(Vector3.up, rotationVelocity.y, Space.World);
            transform.Rotate(Vector3.right, rotationVelocity.x, Space.World);

            rotationVelocity = Vector2.Lerp(rotationVelocity, Vector2.zero, Time.deltaTime * damping);
        }
    }
}
